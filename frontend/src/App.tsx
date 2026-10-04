import { Route, Routes } from 'react-router-dom'
import { RequireRole } from './auth/RequireRole'
import { AppLayout } from './components/layout/AppLayout'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { RoleHomePage } from './pages/RoleHomePage'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="driver" element={<RequireRole roles={['DRIVER']}><RoleHomePage /></RequireRole>} />
        <Route path="owner" element={<RequireRole roles={['OWNER']}><RoleHomePage /></RequireRole>} />
        <Route path="admin" element={<RequireRole roles={['ADMIN']}><RoleHomePage /></RequireRole>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
