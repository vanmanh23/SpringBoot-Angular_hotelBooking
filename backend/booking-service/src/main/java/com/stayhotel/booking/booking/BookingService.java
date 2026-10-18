package com.stayhotel.booking.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayhotel.booking.BookingErrors;
import com.stayhotel.booking.catalog.HotelCatalog;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.booking.inventory.InventoryRepository;
import com.stayhotel.booking.pricing.PriceCalculator;
import com.stayhotel.common.messaging.OutboxWriter;
import com.stayhotel.common.messaging.Topics;
import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.common.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Booking use cases. The catalogue call happens OUTSIDE the database transaction (no connection held during
 * network I/O); persisting the booking and reserving inventory happen in ONE local transaction (design 4.9.1).
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final InventoryRepository inventory;
    private final HotelCatalog catalog;
    private final BookingCodeGenerator codes;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration holdDuration;

    BookingService(BookingRepository bookings, InventoryRepository inventory, HotelCatalog catalog,
                   BookingCodeGenerator codes, ObjectMapper objectMapper, PlatformTransactionManager txManager,
                   OutboxWriter outbox, Clock clock, @Value("${booking.hold-duration:15m}") Duration holdDuration) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.inventory = inventory;
        this.catalog = catalog;
        this.codes = codes;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.holdDuration = holdDuration;
    }

    /** Price preview with an advisory availability flag; reserves nothing. No transaction: it calls hotel-service. */
    public BookingDtos.QuoteResponse quote(BookingDtos.BookingRequest request) {
        int nights = StayPolicy.validateDates(request);
        HotelSnapshot hotel = loadHotel(request);
        List<PriceCalculator.Line> lines = StayPolicy.resolveLines(request, hotel, clock);
        PriceCalculator.PriceBreakdown price = PriceCalculator.price(hotel, lines, nights);
        Map<UUID, Integer> reserved = inventory.maxReserved(lines.stream().map(l -> l.roomType().id()).toList(),
                request.checkInDate(), request.checkOutDate());
        boolean available = lines.stream().allMatch(l ->
                reserved.getOrDefault(l.roomType().id(), 0) + l.quantity() <= l.roomType().totalUnits());
        return new BookingDtos.QuoteResponse(hotel.id(), hotel.name(), request.checkInDate(), request.checkOutDate(),
                nights, price.currency(), toQuoteLines(price), price.subtotal(), price.serviceFeePercent(),
                price.feeAmount(), price.taxRatePercent(), price.taxAmount(), price.discountAmount(),
                price.totalAmount(), available);
    }

    /**
     * Creates a PENDING_PAYMENT booking and reserves its units, idempotently per (user, Idempotency-Key) (BR-21):
     * same key + same body replays the first result; same key + different body is rejected.
     */
    public CreateResult create(CurrentUser user, String idempotencyKey, BookingDtos.BookingRequest request) {
        String hash = hash(request);
        Optional<CreateResult> replayed = tryReplay(user, idempotencyKey, hash);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        int nights = StayPolicy.validateDates(request);
        HotelSnapshot hotel = loadHotel(request);
        List<PriceCalculator.Line> lines = StayPolicy.resolveLines(request, hotel, clock);
        PriceCalculator.PriceBreakdown price = PriceCalculator.price(hotel, lines, nights);

        try {
            BookingDtos.BookingResponse created = tx.execute(s -> {
                Instant now = clock.instant();
                Booking booking = Booking.pending(new Booking.NewBooking(codes.next(request.checkInDate()), user.id(),
                        hotel.id(), hotel.name(), hotel.timeZone(), hotel.checkInTime(), request.checkInDate(),
                        request.checkOutDate(), nights, request.adults(), request.children(),
                        request.contactName().trim(), request.contactEmail().trim(), blankToNull(request.contactPhone()),
                        blankToNull(request.specialRequests()), idempotencyKey, hash), price, now, holdDuration);
                bookings.saveAndFlush(booking); // unique (user, key) is checked here, before touching inventory
                for (PriceCalculator.Line line : lines) { // sorted by room type id: consistent lock order
                    if (!inventory.reserve(line.roomType().id(), request.checkInDate(), request.checkOutDate(),
                            line.quantity(), line.roomType().totalUnits())) {
                        throw BookingErrors.soldOut(); // rolls back the booking and any partial reservation
                    }
                }
                outbox.append(Topics.BOOKING_EVENTS, "BookingCreated", booking.getId(), new BookingEvents.BookingCreated(
                        booking.getId(), booking.getHotelId(), booking.getCheckIn(), booking.getCheckOut(),
                        booking.getTotalAmount(), booking.getCurrencyCode()));
                return BookingDtos.BookingResponse.from(booking);
            });
            log.info("Booking {} created for hotel {} ({} nights)", created.bookingCode(), hotel.id(), nights);
            return new CreateResult(created, false);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same Idempotency-Key won the insert: answer like a replay.
            return tryReplay(user, idempotencyKey, hash).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public BookingDtos.BookingResponse get(CurrentUser user, UUID bookingId) {
        return BookingDtos.BookingResponse.from(loadVisible(user, bookingId));
    }

    @Transactional(readOnly = true)
    public BookingDtos.StatusResponse status(CurrentUser user, UUID bookingId) {
        Booking b = loadVisible(user, bookingId);
        return new BookingDtos.StatusResponse(b.getId(), b.getStatus(), b.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingDtos.BookingSummary> listMine(CurrentUser user, BookingStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(status == null ? bookings.findByUserId(user.id(), pageable)
                : bookings.findByUserIdAndStatus(user.id(), status, pageable), BookingDtos.BookingSummary::from);
    }

    /**
     * Customer (or admin) cancellation (UC-09, P-04). The row lock makes a concurrent cancel/expire/confirm
     * serialize: whichever runs second sees the new status and gets 409.
     */
    @Transactional
    public BookingDtos.BookingResponse cancel(CurrentUser user, UUID bookingId, String reason) {
        Booking booking = bookings.findByIdForUpdate(bookingId)
                .filter(b -> b.isOwnedBy(user.id()) || user.isAdmin())
                .orElseThrow(BookingErrors::bookingNotFound);
        Instant now = clock.instant();
        Boolean refundEligible = null; // nothing was paid for a pending booking
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (!now.isBefore(booking.checkInMoment())) {
                throw BookingErrors.cancellationNotAllowed();
            }
            refundEligible = !now.isAfter(booking.freeCancellationUntil());
        }
        String why = reason != null && !reason.isBlank() ? reason.trim()
                : booking.isOwnedBy(user.id()) ? "Cancelled by customer" : "Cancelled by administrator";
        booking.transitionTo(BookingStatus.CANCELLED, why, user.id().toString(), now);
        booking.markRefundEligibility(refundEligible);
        if (Boolean.TRUE.equals(refundEligible)) {
            booking.updateRefundStatus(Booking.RefundStatus.PENDING, now); // payment-service performs it (BR-26)
        }
        releaseInventory(booking);
        outbox.append(Topics.BOOKING_EVENTS, "BookingCancelled", booking.getId(),
                new BookingEvents.BookingCancelled(booking.getId(), refundEligible));
        return BookingDtos.BookingResponse.from(booking);
    }

    /** Expires one locked, overdue hold and releases its units (used by the lifecycle job). */
    void expire(Booking booking, Instant now) {
        booking.transitionTo(BookingStatus.EXPIRED, "Payment not received within the hold time",
                BookingStatusChange.SYSTEM, now);
        releaseInventory(booking);
        outbox.append(Topics.BOOKING_EVENTS, "BookingExpired", booking.getId(), new BookingEvents.BookingExpired(booking.getId()));
    }

    void releaseInventory(Booking booking) {
        booking.getRooms().stream()
                .sorted((a, b) -> a.getRoomTypeId().compareTo(b.getRoomTypeId()))
                .forEach(r -> inventory.release(r.getRoomTypeId(), booking.getCheckIn(), booking.getCheckOut(),
                        r.getQuantity()));
    }

    private Optional<CreateResult> tryReplay(CurrentUser user, String key, String hash) {
        return tx.execute(s -> bookings.findByUserIdAndIdempotencyKey(user.id(), key).map(b -> {
            if (!b.sameRequest(hash)) {
                throw BookingErrors.idempotencyKeyReused();
            }
            return new CreateResult(BookingDtos.BookingResponse.from(b), true);
        }));
    }

    private HotelSnapshot loadHotel(BookingDtos.BookingRequest request) {
        try {
            return catalog.findActiveHotel(request.hotelId()).orElseThrow(BookingErrors::hotelNotAvailable);
        } catch (HotelCatalog.CatalogUnavailableException e) {
            throw BookingErrors.catalogUnavailable();
        }
    }

    /** Owners and admins see a booking; everyone else gets 404 so ids cannot be probed (anti-IDOR). */
    private Booking loadVisible(CurrentUser user, UUID bookingId) {
        return bookings.findById(bookingId)
                .filter(b -> b.isOwnedBy(user.id()) || user.isAdmin())
                .orElseThrow(BookingErrors::bookingNotFound);
    }

    private static List<BookingDtos.QuoteLine> toQuoteLines(PriceCalculator.PriceBreakdown price) {
        return price.lines().stream().map(l -> new BookingDtos.QuoteLine(l.roomTypeId(), l.roomTypeName(),
                l.quantity(), l.unitPrice(), l.nights(), l.lineSubtotal())).toList();
    }

    private String hash(BookingDtos.BookingRequest request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateResult(BookingDtos.BookingResponse booking, boolean replayed) {
    }
}
