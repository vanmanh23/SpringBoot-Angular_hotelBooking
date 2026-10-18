package com.stayhotel.booking.inventory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-night reserved units (DECISIONS D-018). Reservation is a single conditional UPDATE per room type:
 * PostgreSQL locks each matching row and re-evaluates {@code reserved_units + qty <= total} against the latest
 * committed value, so two concurrent bookings can never both take the last unit (no read-then-write race).
 * Callers must reserve room types in a deterministic order (by id) to avoid lock-order deadlocks.
 */
@Repository
public class InventoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    InventoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves {@code quantity} units on every night in [from, to). Must run inside the booking transaction.
     *
     * @return true if every night had room; false means the caller must roll back (nothing is committed then)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID roomTypeId, LocalDate from, LocalDate to, int quantity, int totalUnits) {
        MapSqlParameterSource p = params(roomTypeId, from, to).addValue("qty", quantity).addValue("total", totalUnits);
        jdbc.update("""
                INSERT INTO room_type_inventory (room_type_id, stay_date, reserved_units)
                SELECT :roomTypeId, d::date, 0 FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') d
                ON CONFLICT (room_type_id, stay_date) DO NOTHING""", p);
        int updated = jdbc.update("""
                UPDATE room_type_inventory SET reserved_units = reserved_units + :qty
                WHERE room_type_id = :roomTypeId AND stay_date >= :from AND stay_date < :to
                  AND reserved_units + :qty <= :total""", p);
        return updated == from.until(to).getDays();
    }

    /**
     * All-or-nothing reservation of several lines without savepoints (late payments): every affected night row is
     * locked FOR UPDATE in a fixed order, capacity is checked under those locks, and only then incremented.
     * @return false (nothing changed) if any night of any line lacks capacity
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveAllOrNothing(List<Line> lines, LocalDate from, LocalDate to) {
        List<Line> ordered = lines.stream().sorted(Comparator.comparing(Line::roomTypeId)).toList();
        for (Line line : ordered) {
            jdbc.update("""
                    INSERT INTO room_type_inventory (room_type_id, stay_date, reserved_units)
                    SELECT :roomTypeId, d::date, 0 FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') d
                    ON CONFLICT (room_type_id, stay_date) DO NOTHING""", params(line.roomTypeId(), from, to));
        }
        for (Line line : ordered) {
            List<Integer> reserved = jdbc.queryForList("""
                    SELECT reserved_units FROM room_type_inventory
                    WHERE room_type_id = :roomTypeId AND stay_date >= :from AND stay_date < :to
                    ORDER BY stay_date FOR UPDATE""", params(line.roomTypeId(), from, to), Integer.class);
            if (reserved.stream().anyMatch(r -> r + line.quantity() > line.totalUnits())) {
                return false;
            }
        }
        for (Line line : ordered) {
            jdbc.update("""
                    UPDATE room_type_inventory SET reserved_units = reserved_units + :qty
                    WHERE room_type_id = :roomTypeId AND stay_date >= :from AND stay_date < :to""",
                    params(line.roomTypeId(), from, to).addValue("qty", line.quantity()));
        }
        return true;
    }

    public record Line(UUID roomTypeId, int quantity, int totalUnits) {
    }

    /** Gives units back (expiry, cancellation). The CHECK constraint guards against going negative. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID roomTypeId, LocalDate from, LocalDate to, int quantity) {
        jdbc.update("""
                UPDATE room_type_inventory SET reserved_units = reserved_units - :qty
                WHERE room_type_id = :roomTypeId AND stay_date >= :from AND stay_date < :to""",
                params(roomTypeId, from, to).addValue("qty", quantity));
    }

    /** Highest reserved count over the stay per room type (0 if never booked). Advisory read, no locks. */
    public Map<UUID, Integer> maxReserved(Collection<UUID> roomTypeIds, LocalDate from, LocalDate to) {
        Map<UUID, Integer> result = new HashMap<>();
        if (roomTypeIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT room_type_id, MAX(reserved_units) AS reserved FROM room_type_inventory
                WHERE room_type_id IN (:ids) AND stay_date >= :from AND stay_date < :to
                GROUP BY room_type_id""",
                new MapSqlParameterSource("ids", roomTypeIds).addValue("from", from).addValue("to", to),
                rs -> {
                    result.put(rs.getObject("room_type_id", UUID.class), rs.getInt("reserved"));
                });
        return result;
    }

    private static MapSqlParameterSource params(UUID roomTypeId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("roomTypeId", roomTypeId).addValue("from", from).addValue("to", to);
    }
}
