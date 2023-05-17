package com.override.orchestrator_service.model;

import brave.internal.Nullable;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.util.UUID;

@Entity
@Table(name="transactions")
@Getter
@Setter
public class Transaction {

    @Id
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @GeneratedValue(generator = "UUID")
    @Column(unique = true)
    private UUID id;

    @Column
    private String message;

    @ManyToOne
    @Nullable
    private Category category;

    @Column
    private Float amount;

    @ManyToOne
    private OverMoneyAccount account;
}
