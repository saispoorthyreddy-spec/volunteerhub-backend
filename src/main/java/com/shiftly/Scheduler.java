package com.shiftly;

import static com.shiftly.domain.Status.*;
import static org.springframework.http.HttpStatus.*;

import com.shiftly.domain.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Capacity validation engine: signup, waitlist, cancellation and waitlist promotion. */
@Service
public class Scheduler {
    private final Repos.Shifts shifts;
    private final Repos.Signups signups;
    private final Repos.Notifications notes;

    public Scheduler(Repos.Shifts shifts, Repos.Signups signups, Repos.Notifications notes) {
        this.shifts = shifts;
        this.signups = signups;
        this.notes = notes;
    }

    @Transactional
    public Signup join(User volunteer, Long shiftId) {
        var shift = shifts.lockById(shiftId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (shift.closed || shift.startTime.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(CONFLICT, "This shift is closed for signups");
        }

        for (var mine : signups.findByVolunteerIdAndStatusIn(volunteer.id, List.of(CONFIRMED, WAITLISTED))) {
            if (mine.shift.id.equals(shift.id)) {
                throw new ResponseStatusException(CONFLICT, "You already joined this shift");
            }
            if (mine.shift.startTime.isBefore(shift.endTime) && shift.startTime.isBefore(mine.shift.endTime)) {
                throw new ResponseStatusException(CONFLICT, "This shift overlaps another shift you joined");
            }
        }

        var signup = new Signup();
        signup.volunteer = volunteer;
        signup.shift = shift;
        signup.status = signups.countByShiftIdAndStatus(shift.id, CONFIRMED) < shift.capacity ? CONFIRMED : WAITLISTED;
        signups.save(signup);

        notify(volunteer, signup.status == CONFIRMED
            ? "You're confirmed for " + label(shift) : "You're on the waitlist for " + label(shift));
        return signup;
    }

    @Transactional
    public Shift edit(Long shiftId, String roleName, LocalDateTime startTime, LocalDateTime endTime, int capacity) {
        var shift = shifts.lockById(shiftId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (shift.closed) {
            throw new ResponseStatusException(CONFLICT, "Closed shifts cannot be edited");
        }
        if (roleName == null || roleName.isBlank() || startTime == null || endTime == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Role, start and end time are required");
        }
        if (!endTime.isAfter(startTime)) {
            throw new ResponseStatusException(BAD_REQUEST, "End time must be after start time");
        }
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(BAD_REQUEST, "Shift cannot start in the past");
        }
        if (capacity < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Capacity must be at least 1");
        }

        long confirmed = signups.countByShiftIdAndStatus(shiftId, CONFIRMED);
        if (capacity < confirmed) {
            throw new ResponseStatusException(CONFLICT, "Capacity cannot be lower than the number of confirmed volunteers (" + confirmed + ")");
        }

        // Editing a shift must not create an overlap for volunteers already attached to it.
        for (var signup : signups.findByShiftIdAndStatusIn(shiftId, List.of(CONFIRMED, WAITLISTED))) {
            for (var other : signups.findByVolunteerIdAndStatusIn(signup.volunteer.id, List.of(CONFIRMED, WAITLISTED))) {
                if (!other.shift.id.equals(shiftId)
                    && other.shift.startTime.isBefore(endTime)
                    && startTime.isBefore(other.shift.endTime)) {
                    throw new ResponseStatusException(CONFLICT,
                        "The new time overlaps another shift for volunteer " + signup.volunteer.name);
                }
            }
        }

        shift.roleName = roleName.trim();
        shift.startTime = startTime;
        shift.endTime = endTime;
        shift.capacity = capacity;
        return shifts.save(shift);
    }

    @Transactional
    public void cancel(User volunteer, Long signupId) {
        var signup = signups.findById(signupId).filter(s -> s.volunteer.id.equals(volunteer.id))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Signup not found"));
        if (signup.status == CANCELLED) return;

        var shift = shifts.lockById(signup.shift.id).orElseThrow();
        boolean freedSpot = signup.status == CONFIRMED;
        signup.status = CANCELLED;
        signups.save(signup);

        if (freedSpot && !shift.closed) {
            // Find waitlisted candidates ordered by signup creation time
            List<Signup> waitlisted = signups.findByShiftIdAndStatusIn(shift.id, List.of(WAITLISTED));
            waitlisted.sort(Comparator.comparing(Signup::getCreatedAt));

            for (Signup candidate : waitlisted) {
                boolean overlaps = signups.findByVolunteerIdAndStatusIn(candidate.volunteer.id, List.of(CONFIRMED))
                    .stream()
                    .anyMatch(c -> !c.shift.id.equals(shift.id) && c.shift.startTime.isBefore(shift.endTime) && shift.startTime.isBefore(c.shift.endTime));

                if (!overlaps) {
                    candidate.status = CONFIRMED;
                    signups.save(candidate);
                    notify(candidate.volunteer, "A spot opened up! You're now confirmed for " + label(shift));
                    break;
                }
            }
        }
    }

    private void notify(User user, String message) {
        var n = new Notification();
        n.user = user;
        n.message = message;
        notes.save(n);
    }

    private String label(Shift s) {
        return s.roleName + " at " + s.event.name + " (" + s.startTime.toLocalDate() + " " + s.startTime.toLocalTime() + ")";
    }
}
