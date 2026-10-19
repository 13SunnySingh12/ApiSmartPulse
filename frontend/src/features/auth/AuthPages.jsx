import { useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { problem } from '../../api/client.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button } from '../../components/ui/Button.jsx';
import { Notice } from '../../components/ui/Feedback.jsx';
import { Field, Input, fieldErrors } from '../../components/ui/Field.jsx';
import { Logo } from '../../components/layout/Logo.jsx';
import { ThemeToggle } from '../../components/layout/AppShell.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';

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
  const [values, setValues] = useState({ email: '', password: '' });
  const form = useAuthForm(
    (v) => ({
      ...(EMAIL.test(v.email.trim()) ? {} : { email: 'Enter a valid email address' }),
      ...(v.password ? {} : { password: 'Enter your password' }),
    }),
    async (v) => {
      await login({ email: v.email.trim(), password: v.password });
      navigate(location.state?.from || '/projects', { replace: true });
    },
  );
  const set = (k) => (e) => setValues({ ...values, [k]: e.target.value });

  return (
    <AuthCard title="Log in" subtitle="Welcome back. Use your work email.">
      <form className="stack auth__form" noValidate onSubmit={(e) => form.onSubmit(e, values)}>
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
            <Input
              {...p}
              type="password"
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
  const [values, setValues] = useState({ name: '', email: '', password: '' });
  const form = useAuthForm(
    (v) => ({
      ...(v.name.trim() && v.name.trim().length <= 80
        ? {}
        : { name: 'Enter your name (up to 80 characters)' }),
      ...(EMAIL.test(v.email.trim()) ? {} : { email: 'Enter a valid email address' }),
      ...(v.password.length >= 10 && /[a-z]/i.test(v.password) && /\d/.test(v.password)
        ? {}
        : { password: 'Use 10–128 characters with at least one letter and one digit' }),
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
          help="At least 10 characters, with a letter and a digit. Common passwords are rejected."
        >
          {(p) => (
            <Input
              {...p}
              type="password"
              autoComplete="new-password"
              value={values.password}
              onChange={set('password')}
            />
          )}
        </Field>
        <Button type="submit" variant="primary" block loading={form.busy}>
          Create account
        </Button>
      </form>
      <p className="small auth__switch">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </AuthCard>
  );
}
