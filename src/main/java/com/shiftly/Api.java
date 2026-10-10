package com.shiftly;

import static com.shiftly.domain.Status.*;
import static org.springframework.http.HttpStatus.*;

import com.shiftly.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class Api {
    public record Register(String name, String email, String password, Role role) {}
    public record Login(String email, String password) {}
    public record EventReq(String name, String description, LocalDate eventDate, String location) {}
    public record ShiftReq(String roleName, LocalDateTime startTime, LocalDateTime endTime, int capacity) {}

    private final Repos.Users users;
    private final Repos.Events events;
    private final Repos.Shifts shifts;
    private final Repos.Signups signups;
    private final Repos.Notifications notes;
    private final Scheduler scheduler;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;

    public Api(Repos.Users users, Repos.Events events, Repos.Shifts shifts, Repos.Signups signups,
               Repos.Notifications notes, Scheduler scheduler, PasswordEncoder encoder, JwtEncoder jwt) {
        this.users = users;
        this.events = events;
        this.shifts = shifts;
        this.signups = signups;
        this.notes = notes;
        this.scheduler = scheduler;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    // ==========================================
    // Authentication
    // ==========================================
    @PostMapping("/auth/register")
    @ResponseStatus(CREATED)
    public Map<String, Object> register(@RequestBody Register r) {
        if (blank(r.name()) || blank(r.email()) || r.role() == null) {
            throw bad("Name, email and role are required");
        }
        if (r.password() == null || r.password().length() < 8) {
            throw bad("Password must be at least 8 characters long");
        }
        String normalizedEmail = r.email().trim().toLowerCase();
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw new ResponseStatusException(CONFLICT, "An account with this email already exists");
        }

        var u = new User();
        u.setName(r.name().trim());
        u.setEmail(normalizedEmail);
        u.setRole(r.role());
        u.setPasswordHash(encoder.encode(r.password()));
        return session(users.save(u));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Login l) {
        String email = l.email() == null ? "" : l.email().trim().toLowerCase();
        var u = users.findByEmail(email)
                .filter(x -> encoder.matches(l.password() == null ? "" : l.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Email or password is incorrect"));
        return session(u);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> meProfile(@AuthenticationPrincipal Jwt j) {
        var u = me(j);
        return Map.of(
                "id", u.getId(),
                "name", u.getName(),
                "email", u.getEmail(),
                "role", u.getRole()
        );
    }

    // ==========================================
    // Events
    // ==========================================
    @GetMapping("/events")
    public List<Map<String, Object>> listEvents() {
        return events.findAllByOrderByEventDateAsc().stream()
                .map(this::eventView)
                .toList();
    }

    @GetMapping("/events/{id}")
    public Map<String, Object> getEvent(@PathVariable Long id) {
        var e = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Event not found"));
        return eventView(e);
    }

    @GetMapping("/events/{id}/shifts")
    public List<Map<String, Object>> getEventShifts(@PathVariable Long id) {
        if (!events.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Event not found");
        }
        return shifts.findByEventIdOrderByStartTimeAsc(id).stream()
                .map(this::view)
                .toList();
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/events")
    @ResponseStatus(CREATED)
    public Map<String, Object> createEvent(@AuthenticationPrincipal Jwt j, @RequestBody EventReq r) {
        if (blank(r.name()) || r.eventDate() == null || blank(r.location())) {
            throw bad("Event name, date and location are required");
        }
        var e = new Event();
        e.setName(r.name().trim());
        e.setDescription(r.description() != null ? r.description().trim() : "");
        e.setEventDate(r.eventDate());
        e.setLocation(r.location().trim());
        e.setOrganizer(me(j));
        return eventView(events.save(e));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PutMapping("/events/{id}")
    public Map<String, Object> updateEvent(@AuthenticationPrincipal Jwt j, @PathVariable Long id, @RequestBody EventReq r) {
        var e = events.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Event not found"));
        if (!e.getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "You can only edit events that you created");
        }
        if (blank(r.name()) || r.eventDate() == null || blank(r.location())) {
            throw bad("Event name, date and location are required");
        }
        e.setName(r.name().trim());
        e.setDescription(r.description() != null ? r.description().trim() : "");
        e.setEventDate(r.eventDate());
        e.setLocation(r.location().trim());
        return eventView(events.save(e));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @DeleteMapping("/events/{id}")
    @ResponseStatus(NO_CONTENT)
    public void deleteEvent(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        var e = events.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Event not found"));
        if (!e.getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "You can only delete events that you created");
        }
        events.delete(e);
    }

    // ==========================================
    // Shifts
    // ==========================================
    @GetMapping("/shifts/{id}")
    public Map<String, Object> getShift(@PathVariable Long id) {
        return view(shifts.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found")));
    }

    @GetMapping("/shifts")
    public List<Map<String, Object>> browseShifts(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) LocalDate date
    ) {
        var from = date == null ? LocalDateTime.now().minusHours(2) : date.atStartOfDay();
        var to = date == null ? LocalDateTime.of(2099, 12, 31, 23, 59) : date.plusDays(1).atStartOfDay();
        return shifts.search(role == null ? "" : role, from, to).stream()
                .filter(s -> eventId == null || s.getEvent().getId().equals(eventId))
                .filter(s -> !s.isClosed())
                .map(this::view)
                .toList();
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/events/{id}/shifts")
    @ResponseStatus(CREATED)
    public Map<String, Object> createShift(@AuthenticationPrincipal Jwt j, @PathVariable Long id, @RequestBody ShiftReq r) {
        var e = events.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Event not found"));
        if (!e.getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "This is not your event");
        }
        if (blank(r.roleName()) || r.startTime() == null || r.endTime() == null) {
            throw bad("Role name, start time and end time are required");
        }
        if (!r.endTime().isAfter(r.startTime())) {
            throw bad("End time must be after start time");
        }
        if (r.capacity() < 1) {
            throw bad("Capacity must be at least 1");
        }

        var s = new Shift();
        s.setEvent(e);
        s.setRoleName(r.roleName().trim());
        s.setStartTime(r.startTime());
        s.setEndTime(r.endTime());
        s.setCapacity(r.capacity());
        return view(shifts.save(s));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PutMapping("/shifts/{id}")
    public Map<String, Object> editShift(@AuthenticationPrincipal Jwt j, @PathVariable Long id, @RequestBody ShiftReq r) {
        var existing = shifts.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (!existing.getEvent().getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "This is not your event");
        }
        var updated = scheduler.edit(id, r.roleName(), r.startTime(), r.endTime(), r.capacity());
        return view(updated);
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/shifts/{id}/close")
    public Map<String, Object> closeShift(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        var s = shifts.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (!s.getEvent().getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "This is not your event");
        }
        s.setClosed(true);
        return view(shifts.save(s));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/shifts/{id}/reopen")
    public Map<String, Object> reopenShift(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        var s = shifts.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (!s.getEvent().getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "This is not your event");
        }
        s.setClosed(false);
        return view(shifts.save(s));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/shifts/{id}/signups")
    public List<Map<String, Object>> getShiftSignups(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        var s = shifts.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shift not found"));
        if (!s.getEvent().getOrganizer().getId().equals(me(j).getId())) {
            throw new ResponseStatusException(FORBIDDEN, "This is not your event");
        }
        return signups.findByShiftIdAndStatusIn(id, List.of(CONFIRMED, WAITLISTED, CANCELLED)).stream()
                .sorted(Comparator.comparing(Signup::getCreatedAt))
                .map(su -> Map.<String, Object>of(
                        "id", su.getId(),
                        "volunteerId", su.getVolunteer().getId(),
                        "volunteerName", su.getVolunteer().getName(),
                        "volunteerEmail", su.getVolunteer().getEmail(),
                        "status", su.getStatus(),
                        "createdAt", su.getCreatedAt().toString()
                ))
                .toList();
    }

    // ==========================================
    // Volunteer Signups
    // ==========================================
    @PreAuthorize("hasRole('VOLUNTEER')")
    @PostMapping("/shifts/{id}/signup")
    @ResponseStatus(CREATED)
    public Map<String, Object> join(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        var s = scheduler.join(me(j), id);
        return Map.of("id", s.getId(), "status", s.getStatus());
    }

    @PreAuthorize("hasRole('VOLUNTEER')")
    @DeleteMapping("/signups/{id}")
    @ResponseStatus(NO_CONTENT)
    public void cancelSignup(@AuthenticationPrincipal Jwt j, @PathVariable Long id) {
        scheduler.cancel(me(j), id);
    }

    @PreAuthorize("hasRole('VOLUNTEER')")
    @GetMapping("/signups/mine")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(@AuthenticationPrincipal Jwt j) {
        return signups.findByVolunteerIdAndStatusIn(me(j).getId(), List.of(CONFIRMED, WAITLISTED)).stream()
                .sorted(Comparator.comparing(s -> s.getShift().getStartTime()))
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "status", s.getStatus(),
                        "createdAt", s.getCreatedAt().toString(),
                        "shift", view(s.getShift())
                ))
                .toList();
    }

    // ==========================================
    // Dashboards
    // ==========================================
    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/dashboard")
    public Map<String, Object> organizerDashboard(@AuthenticationPrincipal Jwt j) {
        Long orgId = me(j).getId();
        var myEvents = events.findByOrganizerIdOrderByEventDateAsc(orgId);
        var myShifts = shifts.findByEventOrganizerIdOrderByStartTimeAsc(orgId);

        List<Map<String, Object>> shiftViews = myShifts.stream().map(this::view).toList();
        long totalCapacity = myShifts.stream().mapToInt(Shift::getCapacity).sum();
        long totalConfirmed = myShifts.stream().mapToLong(s -> signups.countByShiftIdAndStatus(s.getId(), CONFIRMED)).sum();
        long totalWaitlisted = myShifts.stream().mapToLong(s -> signups.countByShiftIdAndStatus(s.getId(), WAITLISTED)).sum();

        return Map.of(
                "events", myEvents.stream().map(this::eventView).toList(),
                "shifts", shiftViews,
                "stats", Map.of(
                        "totalEvents", myEvents.size(),
                        "totalShifts", myShifts.size(),
                        "totalCapacity", totalCapacity,
                        "totalConfirmed", totalConfirmed,
                        "totalWaitlisted", totalWaitlisted
                )
        );
    }

    @PreAuthorize("hasRole('VOLUNTEER')")
    @GetMapping("/volunteer/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> volunteerDashboard(@AuthenticationPrincipal Jwt j) {
        var user = me(j);
        var activeSignups = signups.findByVolunteerIdAndStatusIn(user.getId(), List.of(CONFIRMED, WAITLISTED));
        var userNotes = notes.findByUserIdOrderByCreatedAtDesc(user.getId());

        List<Map<String, Object>> confirmedList = activeSignups.stream()
                .filter(s -> s.getStatus() == CONFIRMED)
                .sorted(Comparator.comparing(s -> s.getShift().getStartTime()))
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "status", s.getStatus(),
                        "shift", view(s.getShift())
                ))
                .toList();

        List<Map<String, Object>> waitlistedList = activeSignups.stream()
                .filter(s -> s.getStatus() == WAITLISTED)
                .sorted(Comparator.comparing(s -> s.getShift().getStartTime()))
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "status", s.getStatus(),
                        "shift", view(s.getShift())
                ))
                .toList();

        return Map.of(
                "user", Map.of("name", user.getName(), "email", user.getEmail()),
                "confirmedShifts", confirmedList,
                "waitlistedShifts", waitlistedList,
                "notifications", userNotes.stream().map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "message", n.getMessage(),
                        "at", n.getCreatedAt().toString()
                )).toList()
        );
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications(@AuthenticationPrincipal Jwt j) {
        return notes.findByUserIdOrderByCreatedAtDesc(me(j).getId()).stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "message", n.getMessage(),
                        "at", n.getCreatedAt().toString()
                )).toList();
    }

    // ==========================================
    // Helpers
    // ==========================================
    private Map<String, Object> eventView(Event e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("description", e.getDescription() != null ? e.getDescription() : "");
        m.put("eventDate", e.getEventDate());
        m.put("location", e.getLocation());
        m.put("organizerId", e.getOrganizer().getId());
        m.put("organizerName", e.getOrganizer().getName());
        long shiftCount = shifts.findByEventIdOrderByStartTimeAsc(e.getId()).size();
        m.put("shiftCount", shiftCount);
        return m;
    }

    private Map<String, Object> view(Shift s) {
        long confirmed = signups.countByShiftIdAndStatus(s.getId(), CONFIRMED);
        long waitlisted = signups.countByShiftIdAndStatus(s.getId(), WAITLISTED);
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.getId());
        m.put("eventId", s.getEvent().getId());
        m.put("eventName", s.getEvent().getName());
        m.put("eventDate", s.getEvent().getEventDate());
        m.put("location", s.getEvent().getLocation());
        m.put("role", s.getRoleName());
        m.put("start", s.getStartTime());
        m.put("end", s.getEndTime());
        m.put("capacity", s.getCapacity());
        m.put("confirmed", confirmed);
        m.put("waitlisted", waitlisted);
        m.put("spotsLeft", Math.max(0, s.getCapacity() - confirmed));
        m.put("fillRate", s.getCapacity() > 0 ? Math.round(100.0 * confirmed / s.getCapacity()) : 0);
        m.put("closed", s.isClosed());
        return m;
    }

    private Map<String, Object> session(User u) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .subject(String.valueOf(u.getId()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(24 * 3600))
                .claim("role", u.getRole().name())
                .claim("name", u.getName())
                .build();
        var header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        var token = jwt.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Map.of(
                "token", token,
                "id", u.getId(),
                "name", u.getName(),
                "email", u.getEmail(),
                "role", u.getRole()
        );
    }

    private User me(Jwt j) {
        return users.findById(Long.valueOf(j.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Session expired, please log in again"));
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
