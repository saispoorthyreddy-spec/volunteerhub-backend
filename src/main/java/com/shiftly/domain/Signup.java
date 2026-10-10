package com.shiftly.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "signups", indexes = @Index(name = "idx_shift_status", columnList = "shift_id,status"))
public class Signup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public User volunteer;

    @ManyToOne(optional = false)
    public Shift shift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Signup() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getVolunteer() { return volunteer; }
    public void setVolunteer(User volunteer) { this.volunteer = volunteer; }

    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
