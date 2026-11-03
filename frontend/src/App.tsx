import { RouterProvider } from 'react-router-dom';
import { router } from './router';

/** Root component: only wires the router, so the shell stays testable in isolation. */
export function App() {
  return <RouterProvider router={router} />;
}

export default App;
