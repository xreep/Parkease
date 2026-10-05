import { Route, Routes } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { AppLayout } from './components/layout/AppLayout'
import { AccountPage } from './pages/AccountPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { RoleHomePage } from './pages/RoleHomePage'
import { SearchPage } from './pages/SearchPage'
import { StatePage } from './pages/StatePage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'
import { AdminHomePage } from './pages/admin/AdminHomePage'
import { AdminLayout } from './pages/admin/AdminLayout'
import { AdminListingReviewPage } from './pages/admin/AdminListingReviewPage'
import { ListingQueuePage } from './pages/admin/ListingQueuePage'
import { OwnerQueuePage } from './pages/admin/OwnerQueuePage'
import { ListingBlocksPage } from './pages/owner/ListingBlocksPage'
import { ListingWizardPage } from './pages/owner/ListingWizardPage'
import { MyListingsPage } from './pages/owner/MyListingsPage'
import { OwnerHomePage } from './pages/owner/OwnerHomePage'
import { OwnerLayout } from './pages/owner/OwnerLayout'
import { OwnerVerificationPage } from './pages/owner/OwnerVerificationPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="in/:stateSlug" element={<StatePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="account" element={<RequireRole><AccountPage /></RequireRole>} />
        <Route path="driver" element={<RequireRole roles={['DRIVER']}><RoleHomePage /></RequireRole>} />
        <Route path="owner" element={<RequireRole roles={['OWNER']}><OwnerLayout /></RequireRole>}>
          <Route index element={<OwnerHomePage />} />
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
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
