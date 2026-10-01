package com.ledgerline.payments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @Query("select p.status as status, count(p) as count from Payment p group by p.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        PaymentStatus getStatus();
        long getCount();
    }
}
