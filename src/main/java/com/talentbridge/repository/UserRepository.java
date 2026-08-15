package com.talentbridge.repository;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
    List<Object[]> countByRoleGrouped();

    @Query("SELECT u.status, COUNT(u) FROM User u GROUP BY u.status")
    List<Object[]> countByStatusGrouped();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);

    // Page — used by UserService.getPendingUsers()
    Page<User> findByStatus(UserStatus status, Pageable pageable);

    // List — used by NotificationService.notifyCoordinatorsNewRegistration()
    List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    List<User> findByRole(UserRole role);
    List<User> findByRoleIn(List<UserRole> roles);
    List<User> findByRoleInAndStatus(List<UserRole> roles, UserStatus status);

}
