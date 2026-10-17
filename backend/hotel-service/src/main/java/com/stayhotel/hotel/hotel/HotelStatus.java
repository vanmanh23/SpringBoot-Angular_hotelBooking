package com.stayhotel.hotel.hotel;

/** Hotel lifecycle (API_CONTRACT.md M2). Only ACTIVE hotels are public (BR-06). */
public enum HotelStatus {
    DRAFT,
    ACTIVE,
    INACTIVE,
    SUSPENDED;

    /**
     * @param admin      caller is ADMIN
     * @param wasApproved hotel was activated by an admin at least once
     */
    public boolean canTransitionTo(HotelStatus target, boolean admin, boolean wasApproved) {
        if (target == this) {
            return false;
        }
        return switch (this) {
            case DRAFT -> admin && target == ACTIVE || admin && target == SUSPENDED;
            case ACTIVE -> target == INACTIVE || admin && target == SUSPENDED;
            case INACTIVE -> target == ACTIVE && (admin || wasApproved) || admin && target == SUSPENDED;
            case SUSPENDED -> admin && (target == ACTIVE || target == INACTIVE);
        };
    }
}
