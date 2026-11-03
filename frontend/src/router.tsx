import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ClaimDetailPage } from './pages/ClaimDetailPage';
import { ClaimsListPage } from './pages/ClaimsListPage';
import { DashboardPage } from './pages/DashboardPage';

/**
 * Route table (blueprint §2.8).
 *
 * `Layout` is the shell and renders the matched child through its `Outlet`; unknown paths
 * fall back to the dashboard instead of showing a dead end.
 */
export const routes = [
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'claims', element: <ClaimsListPage /> },
      { path: 'claims/:id', element: <ClaimDetailPage /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
