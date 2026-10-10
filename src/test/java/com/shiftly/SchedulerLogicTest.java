package com.shiftly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shiftly.domain.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class SchedulerLogicTest {

    @Autowired private Scheduler scheduler;
    @Autowired private Repos.Users users;
    @Autowired private Repos.Events events;
    @Autowired private Repos.Shifts shifts;
    @Autowired private Repos.Signups signups;
    @Autowired private Repos.Notifications notes;

    private User organizer;
    private User volunteer1;
    private User volunteer2;
    private Event event;
    private Shift shift;

    @BeforeEach
    void setUp() {
        signups.deleteAll();
        notes.deleteAll();
        shifts.deleteAll();
        events.deleteAll();
        users.deleteAll();

        organizer = new User(null, "Org Lead", "org@example.com", "hash", Role.ORGANIZER);
        organizer = users.save(organizer);

        volunteer1 = new User(null, "Vol One", "vol1@example.com", "hash", Role.VOLUNTEER);
        volunteer1 = users.save(volunteer1);

        volunteer2 = new User(null, "Vol Two", "vol2@example.com", "hash", Role.VOLUNTEER);
        volunteer2 = users.save(volunteer2);

        event = new Event();
        event.setName("Food Drive");
        event.setDescription("Annual food drive");
        event.setEventDate(LocalDate.now().plusDays(2));
        event.setLocation("Community Hall");
        event.setOrganizer(organizer);
        event = events.save(event);

        shift = new Shift();
        shift.setEvent(event);
        shift.setRoleName("Food Sorter");
        shift.setStartTime(LocalDateTime.now().plusDays(2).withHour(10).withMinute(0));
        shift.setEndTime(LocalDateTime.now().plusDays(2).withHour(13).withMinute(0));
        shift.setCapacity(1); // Capacity 1 to test waitlisting
        shift.setClosed(false);
        shift = shifts.save(shift);
    }

    @Test
    void testCapacityAndWaitlist() {
        // Volunteer 1 joins -> should be CONFIRMED
        Signup signup1 = scheduler.join(volunteer1, shift.getId());
        assertThat(signup1.getStatus()).isEqualTo(Status.CONFIRMED);

        // Volunteer 2 joins -> should be WAITLISTED
        Signup signup2 = scheduler.join(volunteer2, shift.getId());
        assertThat(signup2.getStatus()).isEqualTo(Status.WAITLISTED);

        // Volunteer 1 cancels -> Volunteer 2 should be promoted to CONFIRMED
        scheduler.cancel(volunteer1, signup1.getId());

        Signup updatedSignup2 = signups.findById(signup2.getId()).orElseThrow();
        assertThat(updatedSignup2.getStatus()).isEqualTo(Status.CONFIRMED);
    }

    @Test
    void testOverlapPrevention() {
        // Join initial shift (10:00 - 13:00)
        scheduler.join(volunteer1, shift.getId());

        // Create overlapping shift (11:00 - 14:00)
        Shift overlappingShift = new Shift();
        overlappingShift.setEvent(event);
        overlappingShift.setRoleName("Box Packer");
        overlappingShift.setStartTime(LocalDateTime.now().plusDays(2).withHour(11).withMinute(0));
        overlappingShift.setEndTime(LocalDateTime.now().plusDays(2).withHour(14).withMinute(0));
        overlappingShift.setCapacity(5);
        overlappingShift.setClosed(false);
        final Shift savedShift = shifts.save(overlappingShift);

        // Attempting to join overlapping shift should throw CONFLICT
        assertThatThrownBy(() -> scheduler.join(volunteer1, savedShift.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("overlaps another shift");
    }

    @Test
    void testDuplicateSignupPrevention() {
        scheduler.join(volunteer1, shift.getId());

        assertThatThrownBy(() -> scheduler.join(volunteer1, shift.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already joined");
    }

    @Test
    void testClosedShiftRejection() {
        shift.setClosed(true);
        shifts.save(shift);

        assertThatThrownBy(() -> scheduler.join(volunteer1, shift.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("closed for signups");
    }
}
