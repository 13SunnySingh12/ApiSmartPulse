import { useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { AlertCircle, Eye, EyeOff } from 'lucide-react';
import { problem } from '../../api/client.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import { Notice } from '../../components/ui/Feedback.jsx';
import { Field, Input, fieldErrors } from '../../components/ui/Field.jsx';
import { Logo } from '../../components/layout/Logo.jsx';
import { ThemeToggle } from '../../components/layout/AppShell.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { NEON_AUTH, safeNext } from '../../auth/neonAuth.js';

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

function AuthCard({ title, subtitle, children }) {
  return (
    <div className="auth">
      <div className="auth__theme">
        <ThemeToggle />
      </div>
      <main id="main" className="auth__card glass glass--strong">
        <div className="auth__brand">
          <Logo size={40} />
          <div>
            <p className="auth__product">ApiSmartPulse</p>
            <p className="small muted">
              Know what&apos;s late, who&apos;s overloaded, and which estimates are off.
            </p>
          </div>
        </div>
        <h1 className="auth__title">{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
        {children}
      </main>
    </div>
  );
}

/** Error summary at the top of the form; focused on submit so screen readers hear it (DS-034). */
function ErrorSummary({ summaryRef, message, fields }) {
  if (!message && !fields.length) return null;
  return (
    <div ref={summaryRef} tabIndex={-1} className="notice notice--error" role="alert">
      <AlertCircle className="notice__icon" aria-hidden="true" />
      <div className="notice__body">
        <strong>{message || 'Please fix the highlighted fields.'}</strong>
        {fields.length > 0 && (
          <ul className="auth__errors">
            {fields.map(([id, text]) => (
              <li key={id}>
                <a href={`#${id}`}>{text}</a>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/** Password field with a show/hide toggle; the toggle is a real button with a state label. */
function PasswordInput(props) {
  const [shown, setShown] = useState(false);
  return (
    <div className="password-input">
      <Input {...props} type={shown ? 'text' : 'password'} />
      <IconButton
        size="sm"
        variant="ghost"
        label={shown ? 'Hide password' : 'Show password'}
        aria-pressed={shown}
        offlineOk
        onClick={() => setShown(!shown)}
      >
        {shown ? <EyeOff /> : <Eye />}
      </IconButton>
    </div>
  );
}

/** Monochrome "G" mark: the UI is black-and-white only, so Google's colour logo is not used. */
function GoogleMark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" width="18" height="18">
      <path
        fill="currentColor"
        d="M21.6 12.23c0-.68-.06-1.36-.18-2.02H12v3.83h5.39a4.6 4.6 0 0 1-2 3.02v2.5h3.23c1.9-1.75 2.98-4.32 2.98-7.33ZM12 22c2.7 0 4.96-.9 6.62-2.43l-3.23-2.5c-.9.6-2.04.95-3.39.95-2.6 0-4.81-1.76-5.6-4.12H3.07v2.58A10 10 0 0 0 12 22Zm-5.6-8.1a6 6 0 0 1 0-3.8V7.52H3.07a10 10 0 0 0 0 8.96L6.4 13.9ZM12 5.98c1.47 0 2.79.5 3.83 1.5l2.86-2.86A9.6 9.6 0 0 0 12 2a10 10 0 0 0-8.93 5.52L6.4 10.1C7.19 7.74 9.4 5.98 12 5.98Z"
      />
    </svg>
  );
}

/** Neon Auth builds only: Google sign-in/sign-up (a new Google user gets an account). */
function GoogleSignIn({ next }) {
  const { loginWithGoogle } = useAuth();
  const [state, setState] = useState({ busy: false, error: null });
  if (!NEON_AUTH) return null;
  const start = async () => {
    setState({ busy: true, error: null });
    try {
      await loginWithGoogle(next); // on success the browser leaves for Google
    } catch (err) {
      setState({ busy: false, error: problem(err).detail });
    }
  };
  return (
    <div className="stack auth__social">
      <div className="auth__divider" role="separator">
        <span>or</span>
      </div>
      {state.error && <Notice kind="error">{state.error}</Notice>}
      <Button block icon={<GoogleMark />} loading={state.busy} onClick={start}>
        Continue with Google
      </Button>
    </div>
  );
}

function useAuthForm(validate, submit) {
  const [errors, setErrors] = useState({});
  const [message, setMessage] = useState(null);
  const [locked, setLocked] = useState(null);
  const [busy, setBusy] = useState(false);
  const summaryRef = useRef(null);
  const focusSummary = () => setTimeout(() => summaryRef.current?.focus(), 0);

  const onSubmit = async (e, values) => {
    e.preventDefault();
    const local = validate(values);
    setLocked(null);
    if (Object.keys(local).length) {
      setErrors(local);
      setMessage(null);
      focusSummary();
      return;
    }
    setBusy(true);
    try {
      await submit(values);
    } catch (err) {
      const p = problem(err);
      if (p.code === 'ACCOUNT_LOCKED') setLocked(p.retryAfter);
      setErrors(fieldErrors(p));
      setMessage(p.code === 'VALIDATION_FAILED' ? null : p.detail);
      focusSummary();
    } finally {
      setBusy(false);
    }
  };
  return { errors, message, locked, busy, summaryRef, onSubmit };
}

export function LoginPage() {
  useDocumentTitle('Log in');
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const next = safeNext(location.state?.from || params.get('next'));
  const oauthFailed = params.get('oauth') === 'failed' || params.has('error');
  const [values, setValues] = useState({ email: '', password: '' });
  const form = useAuthForm(
    (v) => ({
      ...(EMAIL.test(v.email.trim()) ? {} : { email: 'Enter a valid email address' }),
      ...(v.password ? {} : { password: 'Enter your password' }),
    }),
    async (v) => {
      await login({ email: v.email.trim(), password: v.password });
      navigate(next, { replace: true });
    },
  );
  const set = (k) => (e) => setValues({ ...values, [k]: e.target.value });

  return (
    <AuthCard title="Log in" subtitle="Welcome back. Use your work email.">
      <form className="stack auth__form" noValidate onSubmit={(e) => form.onSubmit(e, values)}>
        {oauthFailed && !form.message && (
          <Notice kind="error" title="Google sign-in didn't complete">
            It was cancelled or refused. Try again, or log in with your email.
          </Notice>
        )}
        {form.locked ? (
          <Notice kind="warning" title="Account temporarily locked">
            Too many failed attempts. Try again in about {Math.ceil(form.locked / 60)} minutes.
          </Notice>
        ) : (
          <ErrorSummary
            summaryRef={form.summaryRef}
            message={form.message}
            fields={Object.entries(form.errors).map(([k, v]) => [`login-${k}`, v])}
          />
        )}
        <Field id="login-email" label="Email" error={form.errors.email}>
          {(p) => (
            <Input {...p} type="email" autoComplete="email" value={values.email} onChange={set('email')} />
          )}
        </Field>
        <Field id="login-password" label="Password" error={form.errors.password}>
          {(p) => (
            <PasswordInput
              {...p}
              autoComplete="current-password"
              value={values.password}
              onChange={set('password')}
            />
          )}
        </Field>
        <Button type="submit" variant="primary" block loading={form.busy} size="lg">
          Log in
        </Button>
      </form>
      <GoogleSignIn next={next} />
      <p className="small auth__switch">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </AuthCard>
  );
}

export function RegisterPage() {
  useDocumentTitle('Create account');
  const { register } = useAuth();
  const navigate = useNavigate();
  const [values, setValues] = useState({ name: '', email: '', password: '', confirm: '' });
  const form = useAuthForm(
    (v) => ({
      ...(v.name.trim() && v.name.trim().length <= 80
        ? {}
        : { name: 'Enter your name (up to 80 characters)' }),
      ...(EMAIL.test(v.email.trim()) ? {} : { email: 'Enter a valid email address' }),
      ...(v.password.length >= 10 && /[a-z]/i.test(v.password) && /\d/.test(v.password)
        ? {}
        : { password: 'Use 10–128 characters with at least one letter and one digit' }),
      ...(v.confirm === v.password ? {} : { confirm: 'Passwords do not match' }),
    }),
    async (v) => {
      await register({ name: v.name.trim(), email: v.email.trim(), password: v.password });
      navigate('/projects', { replace: true });
    },
  );
  const set = (k) => (e) => setValues({ ...values, [k]: e.target.value });

  return (
    <AuthCard
      title="Create your account"
      subtitle="You'll join as a member. A manager can add you to projects."
    >
      <form className="stack auth__form" noValidate onSubmit={(e) => form.onSubmit(e, values)}>
        <ErrorSummary
          summaryRef={form.summaryRef}
          message={form.message}
          fields={Object.entries(form.errors).map(([k, v]) => [`reg-${k}`, v])}
        />
        <Field id="reg-name" label="Name" error={form.errors.name}>
          {(p) => <Input {...p} autoComplete="name" value={values.name} onChange={set('name')} />}
        </Field>
        <Field id="reg-email" label="Email" error={form.errors.email}>
          {(p) => (
            <Input {...p} type="email" autoComplete="email" value={values.email} onChange={set('email')} />
          )}
        </Field>
        <Field
          id="reg-password"
          label="Password"
          error={form.errors.password}
          help={
            NEON_AUTH
              ? 'At least 10 characters, with a letter and a digit.'
              : 'At least 10 characters, with a letter and a digit. Common passwords are rejected.'
          }
        >
          {(p) => (
            <PasswordInput
              {...p}
              autoComplete="new-password"
              maxLength={128}
              value={values.password}
              onChange={set('password')}
            />
          )}
        </Field>
        <Field id="reg-confirm" label="Confirm password" error={form.errors.confirm}>
          {(p) => (
            <PasswordInput
              {...p}
              autoComplete="new-password"
              maxLength={128}
              value={values.confirm}
              onChange={set('confirm')}
            />
          )}
        </Field>
        <Button type="submit" variant="primary" block loading={form.busy}>
          Create account
        </Button>
      </form>
      <GoogleSignIn next="/projects" />
      <p className="small auth__switch">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </AuthCard>
  );
}

/** Neon Auth returns here after Google; AuthProvider restores the session on load. */
export function AuthCallbackPage() {
  useDocumentTitle('Signing in');
  const { status } = useAuth();
  const [params] = useSearchParams();
  if (status === 'signedIn') return <Navigate to={safeNext(params.get('next'))} replace />;
  if (status === 'signedOut') return <Navigate to="/login?oauth=failed" replace />;
  return (
    <div className="boot" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span className="visually-hidden">Finishing sign-in…</span>
    </div>
  );
}
