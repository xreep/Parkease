package com.smartparking.owner.dashboard;

import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.earning.OwnerEarning;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the earnings ledger as CSV (RFC 4180: CRLF line ends, cells with commas, quotes or line breaks quoted, quotes
 * doubled). Cells that a spreadsheet would read as a formula get a leading apostrophe.
 */
final class EarningsCsv {

    /** Byte order mark, so spreadsheets open the file as UTF-8. */
    static final String BOM = "\uFEFF";

    static final String HEADER = "Booking,Listing,Start (IST),End (IST),Gross,Commission,Net,Status,Paid at,"
            + "Payout reference";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(AvailabilityEvaluator.ZONE);

    private EarningsCsv() {
    }

    static String write(List<OwnerEarning> rows) {
        StringBuilder out = new StringBuilder(BOM).append(HEADER).append("\r\n");
        for (OwnerEarning e : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(e.getBooking().getBookingCode());
            cells.add(e.getBooking().getListing().getTitle());
            cells.add(TIME.format(e.getBooking().getStartTime()));
            cells.add(TIME.format(e.getBooking().getEndTime()));
            cells.add(e.getGross().toPlainString());
            cells.add(e.getCommission().toPlainString());
            cells.add(e.getNet().toPlainString());
            cells.add(e.getStatus().name());
            cells.add(e.getPaidAt() == null ? "" : TIME.format(e.getPaidAt()));
            cells.add(e.getPayoutReference() == null ? "" : e.getPayoutReference());
            out.append(String.join(",", cells.stream().map(EarningsCsv::cell).toList())).append("\r\n");
        }
        return out.toString();
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
