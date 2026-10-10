import { Route, Routes } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { AppLayout } from './components/layout/AppLayout'
import { AccountPage } from './pages/AccountPage'
import { ListingPage } from './pages/ListingPage'
import { CityPage } from './pages/CityPage'
import { HomePage } from './pages/HomePage'
import { NotificationsPage } from './pages/NotificationsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { SearchPage } from './pages/SearchPage'
import { StatePage } from './pages/StatePage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { BookingDetailPage } from './pages/driver/BookingDetailPage'
import { CheckoutPage } from './pages/driver/CheckoutPage'
import { MyBookingsPage } from './pages/driver/MyBookingsPage'
import { PaymentsPage } from './pages/driver/PaymentsPage'
import { DisputeDetailPage } from './pages/driver/DisputeDetailPage'
import { DisputesPage } from './pages/driver/DisputesPage'
import { DriverHomePage } from './pages/driver/DriverHomePage'
import { DriverLayout } from './pages/driver/DriverLayout'
import { VehiclesPage } from './pages/driver/VehiclesPage'
import { AdminBookingDetailPage } from './pages/admin/AdminBookingDetailPage'
import { AdminBookingsPage } from './pages/admin/AdminBookingsPage'
import { AdminLocationsPage } from './pages/admin/AdminLocationsPage'
import { AdminPaymentsPage } from './pages/admin/AdminPaymentsPage'
import { AdminPayoutsPage } from './pages/admin/AdminPayoutsPage'
import { AdminReviewsPage } from './pages/admin/AdminReviewsPage'
import { AdminUsersPage } from './pages/admin/AdminUsersPage'
import { AdminDisputeDetailPage } from './pages/admin/AdminDisputeDetailPage'
import { AdminDisputesPage } from './pages/admin/AdminDisputesPage'
import { AdminHomePage } from './pages/admin/AdminHomePage'
import { AdminAuditPage } from './pages/admin/AdminAuditPage'
import { AdminLayout } from './pages/admin/AdminLayout'
import { AdminListingReviewPage } from './pages/admin/AdminListingReviewPage'
import { AdminReportsPage } from './pages/admin/AdminReportsPage'
import { AdminSettingsPage } from './pages/admin/AdminSettingsPage'
import { ListingQueuePage } from './pages/admin/ListingQueuePage'
import { OwnerQueuePage } from './pages/admin/OwnerQueuePage'
import { ListingBlocksPage } from './pages/owner/ListingBlocksPage'
import { ListingWizardPage } from './pages/owner/ListingWizardPage'
import { MyListingsPage } from './pages/owner/MyListingsPage'
import { OwnerBookingsPage } from './pages/owner/OwnerBookingsPage'
import { OwnerCalendarPage } from './pages/owner/OwnerCalendarPage'
import { OwnerDisputeDetailPage } from './pages/owner/OwnerDisputeDetailPage'
import { OwnerDisputesPage } from './pages/owner/OwnerDisputesPage'
import { OwnerEarningsPage } from './pages/owner/OwnerEarningsPage'
import { OwnerHomePage } from './pages/owner/OwnerHomePage'
import { OwnerLayout } from './pages/owner/OwnerLayout'
import { OwnerReviewsPage } from './pages/owner/OwnerReviewsPage'
import { OwnerVerificationPage } from './pages/owner/OwnerVerificationPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="listings/:id" element={<ListingPage />} />
        <Route path="in/:stateSlug" element={<StatePage />} />
        <Route path="in/:stateSlug/:citySlug" element={<CityPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="account" element={<RequireRole><AccountPage /></RequireRole>} />
        <Route path="notifications" element={<RequireRole><NotificationsPage /></RequireRole>} />
        <Route path="driver" element={<RequireRole roles={['DRIVER']}><DriverLayout /></RequireRole>}>
          <Route index element={<DriverHomePage />} />
          <Route path="bookings" element={<MyBookingsPage />} />
          <Route path="bookings/:id" element={<BookingDetailPage />} />
          <Route path="payments" element={<PaymentsPage />} />
          <Route path="vehicles" element={<VehiclesPage />} />
          <Route path="disputes" element={<DisputesPage />} />
          <Route path="disputes/:id" element={<DisputeDetailPage />} />
        </Route>
        <Route path="checkout/:bookingId" element={<RequireRole roles={['DRIVER']}><CheckoutPage /></RequireRole>} />
        <Route path="owner" element={<RequireRole roles={['OWNER']}><OwnerLayout /></RequireRole>}>
          <Route index element={<OwnerHomePage />} />
          <Route path="bookings" element={<OwnerBookingsPage />} />
          <Route path="earnings" element={<OwnerEarningsPage />} />
          <Route path="calendar" element={<OwnerCalendarPage />} />
          <Route path="reviews" element={<OwnerReviewsPage />} />
          <Route path="disputes" element={<OwnerDisputesPage />} />
          <Route path="disputes/:id" element={<OwnerDisputeDetailPage />} />
          <Route path="verification" element={<OwnerVerificationPage />} />
          <Route path="listings" element={<MyListingsPage />} />
          <Route path="listings/new" element={<ListingWizardPage />} />
          <Route path="listings/:id/edit" element={<ListingWizardPage />} />
          <Route path="listings/:id/blocks" element={<ListingBlocksPage />} />
        </Route>
        <Route path="admin" element={<RequireRole roles={['ADMIN']}><AdminLayout /></RequireRole>}>
          <Route index element={<AdminHomePage />} />
          <Route path="owners" element={<OwnerQueuePage />} />
          <Route path="listings" element={<ListingQueuePage />} />
          <Route path="listings/:id" element={<AdminListingReviewPage />} />
          <Route path="disputes" element={<AdminDisputesPage />} />
          <Route path="disputes/:id" element={<AdminDisputeDetailPage />} />
          <Route path="bookings" element={<AdminBookingsPage />} />
          <Route path="bookings/:id" element={<AdminBookingDetailPage />} />
          <Route path="payments" element={<AdminPaymentsPage />} />
          <Route path="payouts" element={<AdminPayoutsPage />} />
          <Route path="users" element={<AdminUsersPage />} />
          <Route path="reviews" element={<AdminReviewsPage />} />
          <Route path="locations" element={<AdminLocationsPage />} />
          <Route path="reports" element={<AdminReportsPage />} />
          <Route path="settings" element={<AdminSettingsPage />} />
          <Route path="audit" element={<AdminAuditPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
