package com.shiftly;

import com.shiftly.domain.*;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface Repos {
    interface Users extends JpaRepository<User, Long> {
        Optional<User> findByEmail(String email);
    }

    interface Events extends JpaRepository<Event, Long> {
        List<Event> findAllByOrderByEventDateAsc();
        List<Event> findByOrganizerIdOrderByEventDateAsc(Long organizerId);
    }

    interface Shifts extends JpaRepository<Shift, Long> {
        /** Row lock: serialises concurrent signups for the same shift. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from Shift s where s.id = :id")
        Optional<Shift> lockById(@Param("id") Long id);

        @Query("select s from Shift s where lower(s.roleName) like lower(concat('%', :role, '%')) "
             + "and s.startTime >= :from and s.startTime < :to order by s.startTime")
        List<Shift> search(@Param("role") String role, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

        List<Shift> findByEventOrganizerIdOrderByStartTimeAsc(Long organizerId);
        List<Shift> findByEventIdOrderByStartTimeAsc(Long eventId);
    }

    interface Signups extends JpaRepository<Signup, Long> {
        long countByShiftIdAndStatus(Long shiftId, Status status);
        List<Signup> findByVolunteerIdAndStatusIn(Long volunteerId, Collection<Status> statuses);
        List<Signup> findByShiftIdAndStatusIn(Long shiftId, Collection<Status> statuses);
        Optional<Signup> findFirstByShiftIdAndStatusOrderByCreatedAtAsc(Long shiftId, Status status);
        boolean existsByVolunteerIdAndShiftIdAndStatusIn(Long volunteerId, Long shiftId, Collection<Status> statuses);
    }

    interface Notifications extends JpaRepository<Notification, Long> {
        List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    }
}
