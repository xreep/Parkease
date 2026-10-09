package com.smartparking.owner.dashboard;

import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.common.web.PageResponse;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.earning.OwnerEarningRepository.StatusTotal;
import com.smartparking.owner.dashboard.dto.OwnerEarningDto;
import com.smartparking.owner.dashboard.dto.OwnerEarningsDto;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner's earnings ledger: filtered pages, balances over everything, and the CSV export. */
@Service
@RequiredArgsConstructor
public class OwnerEarningsService {

    static final int CSV_MAX_ROWS = 10_000;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("booking.startTime"), Sort.Order.desc("id"));

    private final OwnerEarningRepository earnings;

    @Transactional(readOnly = true)
    public OwnerEarningsDto list(Long ownerId, EarningStatus status, LocalDate from, LocalDate to, int page, int size) {
        Specification<OwnerEarning> filter = filter(ownerId, status, from, to);
        Page<OwnerEarning> result = earnings.findAll(filter,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), NEWEST_FIRST));
        return new OwnerEarningsDto(totals(ownerId), PageResponse.from(result.map(OwnerEarningsService::toDto)));
    }

    /** All rows matching the filters (at most {@value #CSV_MAX_ROWS}), newest booking first, as CSV text. */
    @Transactional(readOnly = true)
    public String csv(Long ownerId, EarningStatus status, LocalDate from, LocalDate to) {
        List<OwnerEarning> rows = earnings.findAll(filter(ownerId, status, from, to),
                PageRequest.of(0, CSV_MAX_ROWS, NEWEST_FIRST)).getContent();
        return EarningsCsv.write(rows);
    }

    private OwnerEarningsDto.Totals totals(Long ownerId) {
        Map<EarningStatus, StatusTotal> byStatus = new EnumMap<>(EarningStatus.class);
        earnings.totalsByStatus(ownerId).forEach(t -> byStatus.put(t.getStatus(), t));
        return new OwnerEarningsDto.Totals(net(byStatus, EarningStatus.HELD), net(byStatus, EarningStatus.PENDING_PAYOUT),
                net(byStatus, EarningStatus.PAID),
                byStatus.containsKey(EarningStatus.REVERSED) ? byStatus.get(EarningStatus.REVERSED).getRows() : 0);
    }

    private static BigDecimal net(Map<EarningStatus, StatusTotal> byStatus, EarningStatus status) {
        StatusTotal total = byStatus.get(status);
        return (total == null ? BigDecimal.ZERO : total.getNet()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Optional filters: status, and the IST date range the booking starts in (inclusive). */
    private static Specification<OwnerEarning> filter(Long ownerId, EarningStatus status, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw DashboardRanges.invalid("from must not be after to");
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("owner").get("id"), ownerId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("booking").get("startTime"), startOf(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("booking").get("startTime"), startOf(to.plusDays(1))));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static OwnerEarningDto toDto(OwnerEarning e) {
        return new OwnerEarningDto(e.getId(), e.getBooking().getId(), e.getBooking().getBookingCode(),
                e.getBooking().getListing().getTitle(), e.getBooking().getStartTime(), e.getBooking().getEndTime(),
                e.getGross(), e.getCommission(), e.getNet(), e.getStatus(), e.getPaidAt(), e.getPayoutReference());
    }
}
