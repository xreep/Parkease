package com.smartparking.invoice;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByBookingId(Long bookingId);

    List<Invoice> findByBookingIdIn(Collection<Long> bookingIds);

    @Query(value = "select nextval('invoice_number_seq')", nativeQuery = true)
    long nextNumber();
}
