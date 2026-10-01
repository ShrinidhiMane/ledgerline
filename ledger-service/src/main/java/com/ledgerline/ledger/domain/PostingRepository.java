package com.ledgerline.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    List<Posting> findByAccountIdOrderByIdDesc(UUID accountId, Pageable page);

    /** Sum of every posting ever written. Must always be zero in a correct ledger. */
    @Query(value = "SELECT COALESCE(SUM(amount_minor), 0) FROM postings", nativeQuery = true)
    Number sumOfAllPostings();

    /** Accounts whose stored balance differs from the sum of their postings. Must be empty. */
    @Query(value = """
            SELECT a.id AS account_id, a.balance_minor AS balance, COALESCE(SUM(p.amount_minor), 0) AS posted
            FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
            GROUP BY a.id, a.balance_minor
            HAVING a.balance_minor <> COALESCE(SUM(p.amount_minor), 0)
            """, nativeQuery = true)
    List<Object[]> balanceMismatches();
}
