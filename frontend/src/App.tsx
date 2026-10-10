import { Route, Routes, useLocation } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AppLayout } from './components/layout/AppLayout'
import { lazyPage } from './lib/lazyPage'

// Every page is its own chunk, loaded when its route is first visited; the layout around it (navbar, footer) is
// eager and shows a spinner where the page will be (see AppLayout). Role areas and heavy pages (map, charts) thereby
// stay out of the main bundle for everyone who never opens them.
const AccountPage = lazyPage(() => import('./pages/AccountPage'), 'AccountPage')
const ListingPage = lazyPage(() => import('./pages/ListingPage'), 'ListingPage')
const CityPage = lazyPage(() => import('./pages/CityPage'), 'CityPage')
const HomePage = lazyPage(() => import('./pages/HomePage'), 'HomePage')
const NotificationsPage = lazyPage(() => import('./pages/NotificationsPage'), 'NotificationsPage')
const NotFoundPage = lazyPage(() => import('./pages/NotFoundPage'), 'NotFoundPage')
const SearchPage = lazyPage(() => import('./pages/SearchPage'), 'SearchPage')
const StatePage = lazyPage(() => import('./pages/StatePage'), 'StatePage')
const ForgotPasswordPage = lazyPage(() => import('./pages/auth/ForgotPasswordPage'), 'ForgotPasswordPage')
const LoginPage = lazyPage(() => import('./pages/auth/LoginPage'), 'LoginPage')
const RegisterPage = lazyPage(() => import('./pages/auth/RegisterPage'), 'RegisterPage')
const ResetPasswordPage = lazyPage(() => import('./pages/auth/ResetPasswordPage'), 'ResetPasswordPage')
const VerifyEmailPage = lazyPage(() => import('./pages/auth/VerifyEmailPage'), 'VerifyEmailPage')
const BookingDetailPage = lazyPage(() => import('./pages/driver/BookingDetailPage'), 'BookingDetailPage')
const CheckoutPage = lazyPage(() => import('./pages/driver/CheckoutPage'), 'CheckoutPage')
const MyBookingsPage = lazyPage(() => import('./pages/driver/MyBookingsPage'), 'MyBookingsPage')
const PaymentsPage = lazyPage(() => import('./pages/driver/PaymentsPage'), 'PaymentsPage')
const DisputeDetailPage = lazyPage(() => import('./pages/driver/DisputeDetailPage'), 'DisputeDetailPage')
const DisputesPage = lazyPage(() => import('./pages/driver/DisputesPage'), 'DisputesPage')
const DriverHomePage = lazyPage(() => import('./pages/driver/DriverHomePage'), 'DriverHomePage')
const DriverLayout = lazyPage(() => import('./pages/driver/DriverLayout'), 'DriverLayout')
const VehiclesPage = lazyPage(() => import('./pages/driver/VehiclesPage'), 'VehiclesPage')
const AdminBookingDetailPage = lazyPage(() => import('./pages/admin/AdminBookingDetailPage'), 'AdminBookingDetailPage')
const AdminBookingsPage = lazyPage(() => import('./pages/admin/AdminBookingsPage'), 'AdminBookingsPage')
const AdminLocationsPage = lazyPage(() => import('./pages/admin/AdminLocationsPage'), 'AdminLocationsPage')
const AdminPaymentsPage = lazyPage(() => import('./pages/admin/AdminPaymentsPage'), 'AdminPaymentsPage')
const AdminPayoutsPage = lazyPage(() => import('./pages/admin/AdminPayoutsPage'), 'AdminPayoutsPage')
const AdminReviewsPage = lazyPage(() => import('./pages/admin/AdminReviewsPage'), 'AdminReviewsPage')
const AdminUsersPage = lazyPage(() => import('./pages/admin/AdminUsersPage'), 'AdminUsersPage')
const AdminDisputeDetailPage = lazyPage(() => import('./pages/admin/AdminDisputeDetailPage'), 'AdminDisputeDetailPage')
const AdminDisputesPage = lazyPage(() => import('./pages/admin/AdminDisputesPage'), 'AdminDisputesPage')
const AdminHomePage = lazyPage(() => import('./pages/admin/AdminHomePage'), 'AdminHomePage')
const AdminAuditPage = lazyPage(() => import('./pages/admin/AdminAuditPage'), 'AdminAuditPage')
const AdminLayout = lazyPage(() => import('./pages/admin/AdminLayout'), 'AdminLayout')
const AdminListingReviewPage = lazyPage(() => import('./pages/admin/AdminListingReviewPage'), 'AdminListingReviewPage')
const AdminReportsPage = lazyPage(() => import('./pages/admin/AdminReportsPage'), 'AdminReportsPage')
const AdminSettingsPage = lazyPage(() => import('./pages/admin/AdminSettingsPage'), 'AdminSettingsPage')
const ListingQueuePage = lazyPage(() => import('./pages/admin/ListingQueuePage'), 'ListingQueuePage')
const OwnerQueuePage = lazyPage(() => import('./pages/admin/OwnerQueuePage'), 'OwnerQueuePage')
const ListingBlocksPage = lazyPage(() => import('./pages/owner/ListingBlocksPage'), 'ListingBlocksPage')
const ListingWizardPage = lazyPage(() => import('./pages/owner/ListingWizardPage'), 'ListingWizardPage')
const MyListingsPage = lazyPage(() => import('./pages/owner/MyListingsPage'), 'MyListingsPage')
const OwnerBookingsPage = lazyPage(() => import('./pages/owner/OwnerBookingsPage'), 'OwnerBookingsPage')
const OwnerCalendarPage = lazyPage(() => import('./pages/owner/OwnerCalendarPage'), 'OwnerCalendarPage')
const OwnerDisputeDetailPage = lazyPage(() => import('./pages/owner/OwnerDisputeDetailPage'), 'OwnerDisputeDetailPage')
const OwnerDisputesPage = lazyPage(() => import('./pages/owner/OwnerDisputesPage'), 'OwnerDisputesPage')
const OwnerEarningsPage = lazyPage(() => import('./pages/owner/OwnerEarningsPage'), 'OwnerEarningsPage')
const OwnerHomePage = lazyPage(() => import('./pages/owner/OwnerHomePage'), 'OwnerHomePage')
const OwnerLayout = lazyPage(() => import('./pages/owner/OwnerLayout'), 'OwnerLayout')
const OwnerReviewsPage = lazyPage(() => import('./pages/owner/OwnerReviewsPage'), 'OwnerReviewsPage')
const OwnerVerificationPage = lazyPage(() => import('./pages/owner/OwnerVerificationPage'), 'OwnerVerificationPage')

export default function App() {
  // Outermost net: a crash in the layout itself. Page crashes are caught lower down, inside AppLayout, so the navbar stays.
  const { pathname } = useLocation()
  return (
    <ErrorBoundary resetKey={pathname}>
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
    </ErrorBoundary>
  )
}
