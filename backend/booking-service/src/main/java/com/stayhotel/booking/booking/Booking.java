package com.stayhotel.booking.booking;

import com.stayhotel.booking.BookingErrors;
import com.stayhotel.booking.pricing.PriceCalculator.PriceBreakdown;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Booking aggregate. Holds an immutable price snapshot (BR-14) and changes status only through
 * {@link #transitionTo}, which enforces the state machine and records history (BR-18).
 */
@Entity
@Table(name = "bookings")
public class Booking {

    /** Free cancellation window before the hotel-local check-in moment (P-04 / D-021). */
    public static final Duration FREE_CANCELLATION_WINDOW = Duration.ofHours(48);

    @Id
    private UUID id;

    @Column(name = "booking_code", nullable = false, updatable = false)
    private String bookingCode;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "hotel_id", nullable = false, updatable = false)
    private UUID hotelId;

    @Column(name = "hotel_name", nullable = false, updatable = false)
    private String hotelName;

    @Column(name = "hotel_time_zone", nullable = false, updatable = false)
    private String hotelTimeZone;

    @Column(name = "check_in_time", nullable = false, updatable = false)
    private LocalTime checkInTime;

    @Column(name = "check_in", nullable = false, updatable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false, updatable = false)
    private LocalDate checkOut;

    @Column(nullable = false, updatable = false)
    private short nights;

    @Column(nullable = false, updatable = false)
    private short adults;

    @Column(nullable = false, updatable = false)
    private short children;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "subtotal_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "service_fee_percent", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal serviceFeePercent;

    @Column(name = "fee_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "tax_rate_percent", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "tax_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "discount_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    private String currencyCode;

    @Column(name = "contact_name", nullable = false, updatable = false)
    private String contactName;

    @Column(name = "contact_email", nullable = false, updatable = false)
    private String contactEmail;

    @Column(name = "contact_phone", updatable = false)
    private String contactPhone;

    @Column(name = "special_requests", updatable = false)
    private String specialRequests;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "refund_eligible")
    private Boolean refundEligible;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "bpchar(64)")
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("roomTypeName ASC")
    @BatchSize(size = 50)
    private List<BookingRoom> rooms = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    @BatchSize(size = 50)
    private List<BookingStatusChange> history = new ArrayList<>();

    protected Booking() {
    }

    public static Booking pending(NewBooking n, PriceBreakdown price, Instant now, Duration hold) {
        Booking b = new Booking();
        b.id = UUID.randomUUID();
        b.bookingCode = n.bookingCode();
        b.userId = n.userId();
        b.hotelId = n.hotelId();
        b.hotelName = n.hotelName();
        b.hotelTimeZone = n.hotelTimeZone();
        b.checkInTime = n.checkInTime();
        b.checkIn = n.checkIn();
        b.checkOut = n.checkOut();
        b.nights = (short) n.nights();
        b.adults = (short) n.adults();
        b.children = (short) n.children();
        b.contactName = n.contactName();
        b.contactEmail = n.contactEmail();
        b.contactPhone = n.contactPhone();
        b.specialRequests = n.specialRequests();
        b.idempotencyKey = n.idempotencyKey();
        b.requestHash = n.requestHash();
        b.subtotalAmount = price.subtotal();
        b.serviceFeePercent = price.serviceFeePercent();
        b.feeAmount = price.feeAmount();
        b.taxRatePercent = price.taxRatePercent();
        b.taxAmount = price.taxAmount();
        b.discountAmount = price.discountAmount();
        b.totalAmount = price.totalAmount();
        b.currencyCode = price.currency();
        price.lines().forEach(l -> b.rooms.add(new BookingRoom(b, l.roomTypeId(), l.roomTypeName(), l.quantity(),
                l.unitPrice(), l.nights(), l.lineSubtotal())));
        b.status = BookingStatus.PENDING_PAYMENT;
        b.expiresAt = now.plus(hold);
        b.createdAt = now;
        b.updatedAt = now;
        b.history.add(new BookingStatusChange(b, null, BookingStatus.PENDING_PAYMENT, "Booking created",
                n.userId().toString(), now));
        return b;
    }

    /** The only way to change status. Throws 409 INVALID_STATUS_TRANSITION for disallowed moves. */
    void transitionTo(BookingStatus target, String reason, String actor, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw BookingErrors.invalidTransition(status, target);
        }
        history.add(new BookingStatusChange(this, status, target, reason, actor, now));
        status = target;
        updatedAt = now;
        switch (target) {
            case CONFIRMED -> {
                confirmedAt = now;
                expiresAt = null;
            }
            case CANCELLED -> {
                cancelledAt = now;
                cancelReason = reason;
                expiresAt = null;
            }
            case EXPIRED -> {
                // keep expiresAt as the historical deadline
            }
            default -> {
            }
        }
    }

    void markRefundEligibility(Boolean eligible) {
        this.refundEligible = eligible;
    }

    /** Hotel-local check-in moment, e.g. 2026-11-10 14:00 Asia/Ho_Chi_Minh. */
    public Instant checkInMoment() {
        return checkIn.atTime(checkInTime).atZone(ZoneId.of(hotelTimeZone)).toInstant();
    }

    public Instant freeCancellationUntil() {
        return checkInMoment().minus(FREE_CANCELLATION_WINDOW);
    }

    public boolean isOwnedBy(UUID user) {
        return userId.equals(user);
    }

    public boolean sameRequest(String hash) {
        return requestHash.equals(hash);
    }

    public UUID getId() {
        return id;
    }

    public String getBookingCode() {
        return bookingCode;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getHotelId() {
        return hotelId;
    }

    public String getHotelName() {
        return hotelName;
    }

    public String getHotelTimeZone() {
        return hotelTimeZone;
    }

    public LocalTime getCheckInTime() {
        return checkInTime;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public int getNights() {
        return nights;
    }

    public int getAdults() {
        return adults;
    }

    public int getChildren() {
        return children;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    public BigDecimal getServiceFeePercent() {
        return serviceFeePercent;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getSpecialRequests() {
        return specialRequests;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Boolean getRefundEligible() {
        return refundEligible;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<BookingRoom> getRooms() {
        return List.copyOf(rooms);
    }

    public List<BookingStatusChange> getHistory() {
        return List.copyOf(history);
    }

    /** Validated input for a new booking (everything except the price, which comes from the calculator). */
    public record NewBooking(String bookingCode, UUID userId, UUID hotelId, String hotelName, String hotelTimeZone,
                             LocalTime checkInTime, LocalDate checkIn, LocalDate checkOut, int nights, int adults,
                             int children, String contactName, String contactEmail, String contactPhone,
                             String specialRequests, String idempotencyKey, String requestHash) {
    }
}
