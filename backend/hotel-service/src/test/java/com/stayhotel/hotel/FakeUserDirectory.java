package com.stayhotel.hotel;

import com.stayhotel.common.error.BusinessException;
import com.stayhotel.hotel.user.UserDirectory;
import org.springframework.http.HttpStatus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory auth-service stand-in; the HTTP adapter has its own test (HttpUserDirectoryTest). */
public class FakeUserDirectory implements UserDirectory {

    private final Set<UUID> activeManagers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> others = ConcurrentHashMap.newKeySet();

    public void manager(UUID id) {
        activeManagers.add(id);
    }

    public void notManager(UUID id) {
        others.add(id);
    }

    @Override
    public void requireActiveHotelManager(UUID userId, String callerBearerToken) {
        if (activeManagers.contains(userId)) {
            return;
        }
        if (others.contains(userId)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_NOT_HOTEL_MANAGER", "not a manager");
        }
        throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }
}
