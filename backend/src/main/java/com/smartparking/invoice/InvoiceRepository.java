package com.smartparking.invoice;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByBookingId(Long bookingId);

    @Query(value = "select nextval('invoice_number_seq')", nativeQuery = true)
    long nextNumber();
}
