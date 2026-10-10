package com.shiftly;

import static org.assertj.core.api.Assertions.assertThat;

import com.shiftly.domain.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AppStartupTest {

    @Autowired
    private Repos.Users users;

    @Autowired
    private Repos.Events events;

    @Autowired
    private Repos.Shifts shifts;

    @Autowired
    private Repos.Signups signups;

    @Autowired
    private Repos.Notifications notes;

    @Test
    void contextLoadsAndRepositoriesInitialize() {
        assertThat(users).isNotNull();
        assertThat(events).isNotNull();
        assertThat(shifts).isNotNull();
        assertThat(signups).isNotNull();
        assertThat(notes).isNotNull();
    }

    @Test
    void testEntityPersistenceAndCustomQueries() {
        // Create user
        User user = new User();
        user.setName("Alice Test");
        user.setEmail("alice.test@example.com");
        user.setRole(Role.ORGANIZER);
        user.setPasswordHash("hashedpwd");
        user = users.save(user);
        assertThat(user.getId()).isNotNull();

        // Create event
        Event event = new Event();
        event.setName("Community Garden Clean-up");
        event.setDescription("Annual garden clean-up");
        event.setEventDate(LocalDate.now().plusDays(5));
        event.setLocation("Central Park");
        event.setOrganizer(user);
        event = events.save(event);
        assertThat(event.getId()).isNotNull();

        // Create shift
        Shift shift = new Shift();
        shift.setEvent(event);
        shift.setRoleName("Tree Planting");
        shift.setStartTime(LocalDateTime.now().plusDays(5).withHour(9).withMinute(0));
        shift.setEndTime(LocalDateTime.now().plusDays(5).withHour(12).withMinute(0));
        shift.setCapacity(5);
        shift.setClosed(false);
        shift = shifts.save(shift);
        assertThat(shift.getId()).isNotNull();

        // Test custom query in Shifts repository (the one that previously failed with UnknownEntityException)
        var lockedShift = shifts.lockById(shift.getId());
        assertThat(lockedShift).isPresent();
        assertThat(lockedShift.get().getRoleName()).isEqualTo("Tree Planting");

        var searchResults = shifts.search("Plant", LocalDateTime.now(), LocalDateTime.now().plusDays(10));
        assertThat(searchResults).isNotEmpty();
    }
}
