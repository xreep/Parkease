package com.smartparking.invoice;

import com.smartparking.booking.Booking;
import com.smartparking.common.model.VehicleType;
import com.smartparking.common.util.Ist;
import com.smartparking.listing.ParkingListing;
import com.smartparking.payment.Payment;
import com.smartparking.user.User;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders the tax invoice / receipt of a paid booking as an A4 PDF. Reads lazy associations of the invoice, so call it
 * inside a transaction.
 *
 * <p>Amounts are written as "Rs. 67.08": the built-in PDF fonts have no rupee sign (and bundling a font only for one
 * glyph is not worth it). Characters the font cannot show are skipped by the PDF library rather than failing.
 */
public final class ReceiptPdf {

    private static final Color BRAND = new Color(0x04, 0x78, 0x57);
    private static final Color MUTED = new Color(0x64, 0x74, 0x8b);
    private static final Color RULE = new Color(0xcb, 0xd5, 0xe1);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, Font.NORMAL, BRAND);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Font.NORMAL, Color.BLACK);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.NORMAL, MUTED);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, Font.NORMAL, Color.BLACK);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, Font.NORMAL, Color.BLACK);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, MUTED);

    private ReceiptPdf() {
    }

    /** @param gstPercent the GST rate charged on the platform fee, for the line label (e.g. 18) */
    public static byte[] render(Invoice invoice, BigDecimal gstPercent) {
        Booking booking = invoice.getBooking();
        Payment payment = invoice.getPayment();
        User driver = booking.getDriver();
        ParkingListing listing = booking.getListing();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            PdfPTable header = table(new float[] {60, 40});
            header.addCell(cell("ParkEase", TITLE, Element.ALIGN_LEFT, Rectangle.BOTTOM));
            header.addCell(cell("Tax invoice / Receipt", HEADING, Element.ALIGN_RIGHT, Rectangle.BOTTOM));
            document.add(header);

            PdfPTable meta = table(new float[] {30, 70});
            addRow(meta, "Invoice number", invoice.getInvoiceNumber());
            addRow(meta, "Issue date (IST)", Ist.formatDateTime(invoice.getIssuedAt()) + " IST");
            addRow(meta, "Booking code", booking.getBookingCode());
            document.add(meta);

            document.add(section("BILLED TO"));
            document.add(new Paragraph(driver.getName(), BOLD));
            document.add(new Paragraph(driver.getEmail(), BODY));

            document.add(section("PARKING"));
            document.add(new Paragraph(listing.getTitle(), BOLD));
            document.add(new Paragraph(listing.getAddress(), BODY));
            PdfPTable stay = table(new float[] {30, 70});
            addRow(stay, "Slot", booking.getSlot().getLabel());
            addRow(stay, "Vehicle", booking.getPlateNumber() + " (" + vehicleLabel(booking.getVehicleType()) + ")");
            addRow(stay, "Parking window", Ist.format(booking.getStartTime()) + " to " + Ist.format(booking.getEndTime())
                    + " (IST)");
            document.add(stay);

            document.add(section("CHARGES"));
            PdfPTable items = table(new float[] {75, 25});
            items.addCell(cell("Description", SECTION, Element.ALIGN_LEFT, Rectangle.BOTTOM));
            items.addCell(cell("Amount", SECTION, Element.ALIGN_RIGHT, Rectangle.BOTTOM));
            addItem(items, "Parking (" + booking.getPricingBreakdown() + ")", booking.getBaseAmount(), BODY);
            addItem(items, "Platform fee", booking.getPlatformFee(), BODY);
            addItem(items, "GST on platform fee (" + gstPercent.stripTrailingZeros().toPlainString() + "%)",
                    booking.getGstAmount(), BODY);
            items.addCell(cell("Total paid", BOLD, Element.ALIGN_LEFT, Rectangle.TOP));
            items.addCell(cell(money(booking.getTotalAmount()), BOLD, Element.ALIGN_RIGHT, Rectangle.TOP));
            document.add(items);

            document.add(section("PAYMENT"));
            PdfPTable pay = table(new float[] {30, 70});
            addRow(pay, "Payment ID", payment.getPaymentId() == null ? "-" : payment.getPaymentId());
            addRow(pay, "Method", payment.getMethod() == null || payment.getMethod().isBlank() ? "Online"
                    : payment.getMethod());
            if (booking.getRefundAmount().signum() > 0) {
                addRow(pay, "Refunded", money(booking.getRefundAmount()));
            }
            document.add(pay);

            Paragraph footer = new Paragraph("This is a computer-generated receipt.", SMALL);
            footer.setSpacingBefore(36);
            document.add(footer);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the receipt PDF", e);
        }
        return out.toByteArray();
    }

    static String money(BigDecimal amount) {
        return "Rs. " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String vehicleLabel(VehicleType type) {
        return type == VehicleType.TWO_WHEELER ? "two-wheeler" : "four-wheeler";
    }

    private static Paragraph section(String title) {
        Paragraph p = new Paragraph(title, SECTION);
        p.setSpacingBefore(18);
        p.setSpacingAfter(4);
        return p;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try {
            table.setWidths(widths);
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        table.setSpacingBefore(6);
        return table;
    }

    private static void addRow(PdfPTable table, String label, String value) {
        table.addCell(cell(label, SMALL, Element.ALIGN_LEFT, Rectangle.NO_BORDER));
        table.addCell(cell(value, BODY, Element.ALIGN_LEFT, Rectangle.NO_BORDER));
    }

    private static void addItem(PdfPTable table, String description, BigDecimal amount, Font font) {
        table.addCell(cell(description, font, Element.ALIGN_LEFT, Rectangle.NO_BORDER));
        table.addCell(cell(money(amount), font, Element.ALIGN_RIGHT, Rectangle.NO_BORDER));
    }

    private static PdfPCell cell(String text, Font font, int align, int border) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setBorder(border);
        cell.setBorderColor(RULE);
        cell.setPadding(5);
        return cell;
    }
}
