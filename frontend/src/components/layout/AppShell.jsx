import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useMatch, useNavigate } from 'react-router';
import {
  Brain,
  ChevronLeft,
  ChevronRight,
  FolderKanban,
  KeyRound,
  LogOut,
  Monitor,
  Moon,
  Sun,
  UserRound,
  Users,
  WifiOff,
} from 'lucide-react';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { useTheme } from '../../hooks/useTheme.jsx';
import { useOnline } from '../../hooks/useOnline.js';
import { Avatar } from '../ui/Chips.jsx';
import { Menu, MenuItem, MenuLabel, MenuSeparator, Tooltip } from '../ui/Menu.jsx';
import { Segmented } from '../ui/Segmented.jsx';
import { Logo } from './Logo.jsx';

function readCollapsed() {
  try {
    return window.localStorage.getItem('asp-sidebar') === 'collapsed';
  } catch {
    return false;
  }
}

export function AppShell() {
  const { user, logout } = useAuth();
  const online = useOnline();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const isAdmin = user?.role === 'ADMIN';

  const toggle = () => {
    setCollapsed((c) => {
      try {
        window.localStorage.setItem('asp-sidebar', c ? 'open' : 'collapsed');
      } catch {
        /* remembered for this session only */
      }
      return !c;
    });
  };

  const nav = [
    { to: '/projects', label: 'Projects', icon: FolderKanban },
    { to: '/api-keys', label: 'API keys', icon: KeyRound },
    ...(isAdmin
      ? [
          { to: '/admin/users', label: 'Users', icon: Users },
          { to: '/admin/ml', label: 'Models', icon: Brain },
        ]
      : []),
    { to: '/profile', label: 'Profile', icon: UserRound },
  ];

  return (
    <div className={`shell ${collapsed ? 'shell--collapsed' : ''}`}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <aside className="sidebar glass glass--md" aria-label="Sidebar">
        <Link to="/projects" className="sidebar__brand" aria-label="ApiSmartPulse home">
          <Logo />
          <span className="sidebar__text">ApiSmartPulse</span>
        </Link>
        <nav aria-label="Primary">
          <ul className="sidebar__nav">
            {nav.map(({ to, label, icon: Icon }) => (
              <li key={to}>
                <Tooltip content={collapsed ? label : null}>
                  <NavLink to={to} className="sidebar__link">
                    <Icon aria-hidden="true" />
                    <span className="sidebar__text">{label}</span>
                  </NavLink>
                </Tooltip>
              </li>
            ))}
          </ul>
        </nav>
        <button
          type="button"
          className="sidebar__collapse"
          onClick={toggle}
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? <ChevronRight aria-hidden="true" /> : <ChevronLeft aria-hidden="true" />}
          <span className="sidebar__text">Collapse</span>
        </button>
      </aside>

      <div className="shell__main">
        <header className="topbar glass glass--md">
          <Breadcrumbs />
          <div className="topbar__actions">
            <ThemeToggle />
            <Menu
              label="Account"
              trigger={
                <button type="button" className="topbar__user">
                  <Avatar name={user?.name} size="lg" />
                  <span className="topbar__name">{user?.name}</span>
                </button>
              }
            >
              <MenuLabel>
                {user?.email} · {user?.role}
              </MenuLabel>
              <MenuItem icon={<UserRound />} onSelect={() => navigate('/profile')}>
                Profile
              </MenuItem>
              <MenuSeparator />
              <MenuItem icon={<LogOut />} onSelect={() => logout().then(() => navigate('/login'))}>
                Log out
              </MenuItem>
            </Menu>
          </div>
        </header>

        {!online && (
          <div className="offline glass" role="status">
            <WifiOff aria-hidden="true" /> You&apos;re offline. Changes won&apos;t be saved until you
            reconnect.
          </div>
        )}

        <main id="main" className="content" tabIndex={-1}>
          <Outlet />
        </main>
      </div>

      <nav className="tabbar glass glass--md" aria-label="Primary">
        {nav
          .filter((n) => !n.to.startsWith('/admin') || n.to === '/admin/users')
          .map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className="tabbar__link">
              <Icon aria-hidden="true" />
              <span>
                {to === '/admin/users'
                  ? 'Admin'
                  : to === '/profile'
                    ? 'Me'
                    : label === 'API keys'
                      ? 'Keys'
                      : label}
              </span>
            </NavLink>
          ))}
      </nav>
    </div>
  );
}

const SECTIONS = {
  board: 'Board',
  ask: 'Ask',
  workload: 'Workload',
  analytics: 'Analytics',
  settings: 'Settings',
};

function Breadcrumbs() {
  const location = useLocation();
  const project = useMatch('/projects/:key/*');
  const parts = location.pathname.split('/').filter(Boolean);
  const crumbs = [];
  if (parts[0] === 'projects') {
    crumbs.push({ to: '/projects', label: 'Projects' });
    if (project) {
      crumbs.push({ to: `/projects/${project.params.key}/board`, label: project.params.key });
      if (parts[2] === 'tasks' && parts[3]) crumbs.push({ label: parts[3] });
      else if (SECTIONS[parts[2]]) crumbs.push({ label: SECTIONS[parts[2]] });
    }
  } else if (parts[0] === 'admin') {
    crumbs.push({ label: 'Admin' }, { label: parts[1] === 'ml' ? 'Models' : 'Users' });
  } else if (parts[0] === 'api-keys') crumbs.push({ label: 'API keys' });
  else if (parts[0] === 'profile') crumbs.push({ label: 'Profile' });
  return (
    <nav aria-label="Breadcrumb" className="crumbs">
      <ol>
        {crumbs.map((c, i) => (
          <li key={i}>
            {c.to && i < crumbs.length - 1 ? (
              <Link to={c.to}>{c.label}</Link>
            ) : (
              <span aria-current={i === crumbs.length - 1 ? 'page' : undefined}>{c.label}</span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  return (
    <Segmented
      label="Theme"
      value={theme}
      onChange={setTheme}
      options={[
        { value: 'system', icon: <Monitor aria-hidden="true" />, ariaLabel: 'System theme' },
        { value: 'light', icon: <Sun aria-hidden="true" />, ariaLabel: 'Light theme' },
        { value: 'dark', icon: <Moon aria-hidden="true" />, ariaLabel: 'Dark theme' },
      ]}
    />
  );
}
