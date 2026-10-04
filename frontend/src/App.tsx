import { Route, Routes } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { AppLayout } from './components/layout/AppLayout'
import { AccountPage } from './pages/AccountPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { RoleHomePage } from './pages/RoleHomePage'
import { StatePage } from './pages/StatePage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage'
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="in/:stateSlug" element={<StatePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="account" element={<RequireRole><AccountPage /></RequireRole>} />
        <Route path="driver" element={<RequireRole roles={['DRIVER']}><RoleHomePage /></RequireRole>} />
        <Route path="owner" element={<RequireRole roles={['OWNER']}><RoleHomePage /></RequireRole>} />
        <Route path="admin" element={<RequireRole roles={['ADMIN']}><RoleHomePage /></RequireRole>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
