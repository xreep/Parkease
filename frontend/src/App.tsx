import { Route, Routes, useLocation } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AppLayout } from './components/layout/AppLayout'
import * as pages from './pages/lazyPages'

export default function App() {
  // Outermost net: a crash in the layout itself. Page crashes are caught lower down, inside AppLayout, so the navbar stays.
  const { pathname } = useLocation()
  return (
    <ErrorBoundary resetKey={pathname}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<pages.HomePage />} />
          <Route path="search" element={<pages.SearchPage />} />
          <Route path="listings/:id" element={<pages.ListingPage />} />
          <Route path="in/:stateSlug" element={<pages.StatePage />} />
          <Route path="in/:stateSlug/:citySlug" element={<pages.CityPage />} />
          <Route path="login" element={<pages.LoginPage />} />
          <Route path="register" element={<pages.RegisterPage />} />
          <Route path="forgot-password" element={<pages.ForgotPasswordPage />} />
          <Route path="reset-password" element={<pages.ResetPasswordPage />} />
          <Route path="verify-email" element={<pages.VerifyEmailPage />} />
          <Route path="account" element={<RequireRole><pages.AccountPage /></RequireRole>} />
          <Route path="notifications" element={<RequireRole><pages.NotificationsPage /></RequireRole>} />
          <Route path="driver" element={<RequireRole roles={['DRIVER']}><pages.DriverLayout /></RequireRole>}>
            <Route index element={<pages.DriverHomePage />} />
            <Route path="bookings" element={<pages.MyBookingsPage />} />
            <Route path="bookings/:id" element={<pages.BookingDetailPage />} />
            <Route path="payments" element={<pages.PaymentsPage />} />
            <Route path="vehicles" element={<pages.VehiclesPage />} />
            <Route path="disputes" element={<pages.DisputesPage />} />
            <Route path="disputes/:id" element={<pages.DisputeDetailPage />} />
          </Route>
          <Route path="checkout/:bookingId" element={<RequireRole roles={['DRIVER']}><pages.CheckoutPage /></RequireRole>} />
          <Route path="owner" element={<RequireRole roles={['OWNER']}><pages.OwnerLayout /></RequireRole>}>
            <Route index element={<pages.OwnerHomePage />} />
            <Route path="bookings" element={<pages.OwnerBookingsPage />} />
            <Route path="earnings" element={<pages.OwnerEarningsPage />} />
            <Route path="calendar" element={<pages.OwnerCalendarPage />} />
            <Route path="reviews" element={<pages.OwnerReviewsPage />} />
            <Route path="disputes" element={<pages.OwnerDisputesPage />} />
            <Route path="disputes/:id" element={<pages.OwnerDisputeDetailPage />} />
            <Route path="verification" element={<pages.OwnerVerificationPage />} />
            <Route path="listings" element={<pages.MyListingsPage />} />
            <Route path="listings/new" element={<pages.ListingWizardPage />} />
            <Route path="listings/:id/edit" element={<pages.ListingWizardPage />} />
            <Route path="listings/:id/blocks" element={<pages.ListingBlocksPage />} />
          </Route>
          <Route path="admin" element={<RequireRole roles={['ADMIN']}><pages.AdminLayout /></RequireRole>}>
            <Route index element={<pages.AdminHomePage />} />
            <Route path="owners" element={<pages.OwnerQueuePage />} />
            <Route path="listings" element={<pages.ListingQueuePage />} />
            <Route path="listings/:id" element={<pages.AdminListingReviewPage />} />
            <Route path="disputes" element={<pages.AdminDisputesPage />} />
            <Route path="disputes/:id" element={<pages.AdminDisputeDetailPage />} />
            <Route path="bookings" element={<pages.AdminBookingsPage />} />
            <Route path="bookings/:id" element={<pages.AdminBookingDetailPage />} />
            <Route path="payments" element={<pages.AdminPaymentsPage />} />
            <Route path="payouts" element={<pages.AdminPayoutsPage />} />
            <Route path="users" element={<pages.AdminUsersPage />} />
            <Route path="reviews" element={<pages.AdminReviewsPage />} />
            <Route path="locations" element={<pages.AdminLocationsPage />} />
            <Route path="reports" element={<pages.AdminReportsPage />} />
            <Route path="settings" element={<pages.AdminSettingsPage />} />
            <Route path="audit" element={<pages.AdminAuditPage />} />
          </Route>
          <Route path="*" element={<pages.NotFoundPage />} />
        </Route>
      </Routes>
    </ErrorBoundary>
  )
}
