package com.smartparking.booking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingEventRepository extends JpaRepository<BookingEvent, Long> {

    boolean existsByBookingIdAndToStatus(Long bookingId, BookingStatus toStatus);

    List<BookingEvent> findByBookingIdOrderByCreatedAtAscIdAsc(Long bookingId);
}
