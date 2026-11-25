import { useEffect, useState } from 'react';
import { Link, Route, Routes } from 'react-router-dom';
import { setUnauthorizedHandler } from './api';
import LoginForm from './components/LoginForm';
import HomePage from './pages/HomePage';
import JobPage from './pages/JobPage';

/** Page frame and routes. Shows the login form instead of the page whenever the backend answers 401. */
export default function App() {
  const [needsLogin, setNeedsLogin] = useState(false);

  useEffect(() => {
    setUnauthorizedHandler(() => setNeedsLogin(true));
  }, []);

  return (
    <div className="mx-auto max-w-5xl px-4 py-6">
      <header className="mb-6 flex items-center justify-between">
        <Link to="/" className="text-2xl font-bold tracking-tight">
          Clipper
        </Link>
      </header>
      {needsLogin ? (
        <LoginForm onLoggedIn={() => setNeedsLogin(false)} />
      ) : (
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/jobs/:id" element={<JobPage />} />
        </Routes>
      )}
    </div>
  );
}
