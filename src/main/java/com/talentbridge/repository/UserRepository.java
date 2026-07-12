package com.talentbridge.repository;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Page — used by UserService.getPendingUsers()
    Page<User> findByStatus(UserStatus status, Pageable pageable);

    // List — used by NotificationService.notifyCoordinatorsNewRegistration()
    List<User> findByRoleAndStatus(UserRole role, UserStatus status);

    List<User> findByRole(UserRole role);
    List<User> findByRoleIn(List<UserRole> roles);

    @Query("SELECT u FROM User u WHERE u.role = 'COORDINATOR' AND u.status = 'APPROVED'")
    List<User> findAllCoordinators();
}
