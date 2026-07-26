package com.talentbridge.repository;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Page — used by UserService.getPendingUsers()
    Page<User> findByStatus(UserStatus status, Pageable pageable);

    // List — used by NotificationService.notifyCoordinatorsNewRegistration()
    List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    List<User> findByRole(UserRole role);
    List<User> findByRoleIn(List<UserRole> roles);

}
