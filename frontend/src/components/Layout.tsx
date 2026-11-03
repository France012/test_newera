import type { ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';

export interface LayoutProps {
  /** Explicit content; when omitted the nested route renders through {@link Outlet}. */
  children?: ReactNode;
}

/**
 * Application shell: brand, primary navigation and content container.
 *
 * Sticky header + a single `<main>` landmark. The nav uses `NavLink` so the active section is
 * announced through `aria-current` instead of relying on colour alone.
 */
export function Layout({ children }: LayoutProps) {
  return (
    <div className="app">
      <a className="skip-link" href="#main-content">
        Salta al contenuto
      </a>
      <header className="app__header">
        <div className="app__header-inner">
          <div className="brand">
            <span className="brand__mark" aria-hidden="true">
              CS
            </span>
            <span className="brand__text">
              <span className="brand__name">Claims Suite</span>
              <span className="brand__tagline">Gestione pratiche assicurative</span>
            </span>
          </div>
          <nav className="app__nav" aria-label="Navigazione principale">
            <NavLink to="/" end className={navClass}>
              Dashboard
            </NavLink>
            <NavLink to="/claims" className={navClass}>
              Pratiche
            </NavLink>
          </nav>
        </div>
      </header>

      <main id="main-content" className="app__main">
        {children ?? <Outlet />}
      </main>

      <footer className="app__footer">
        <span>Claims Suite · v0.1.0</span>
        <span className="app__footer-sep" aria-hidden="true">
          ·
        </span>
        <span>Ambiente di collaudo</span>
      </footer>
    </div>
  );
}

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'app__nav-link app__nav-link--active' : 'app__nav-link';
}
