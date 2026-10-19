import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { problem } from '../../api/client.js';
import { me } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button } from '../../components/ui/Button.jsx';
import { Field, Input, fieldErrors } from '../../components/ui/Field.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { ThemeToggle } from '../../components/layout/AppShell.jsx';

export default function ProfilePage() {
  useDocumentTitle('Profile');
  const { user, setUser } = useAuth();
  const toast = useToast();
  const [name, setName] = useState(user.name);
  const [nameError, setNameError] = useState(null);
  const [pw, setPw] = useState({ currentPassword: '', newPassword: '' });
  const [pwErrors, setPwErrors] = useState({});

  const rename = useMutation({
    mutationFn: () => me.update({ name: name.trim() }),
    onSuccess: (u) => {
      setUser(u);
      setNameError(null);
      toast.success('Name updated');
    },
    onError: (e) =>
      setNameError(
        problem(e).errors[0]?.message ? `Name ${problem(e).errors[0].message}` : problem(e).detail,
      ),
  });
  const changePw = useMutation({
    mutationFn: () => me.changePassword(pw),
    onSuccess: () => {
      setPw({ currentPassword: '', newPassword: '' });
      setPwErrors({});
      toast.success('Password changed');
    },
    onError: (e) => {
      const p = problem(e);
      setPwErrors({ ...fieldErrors(p), _form: p.errors.length ? null : p.detail });
    },
  });

  return (
    <div className="page narrow">
      <div className="page__header">
        <div>
          <h1>Profile</h1>
          <p>
            {user.email} · {user.role.toLowerCase()}
          </p>
        </div>
      </div>
      <section className="glass panel" aria-labelledby="name-h">
        <h2 id="name-h" className="panel__title">
          Your name
        </h2>
        <form
          className="row align-end-row"
          onSubmit={(e) => {
            e.preventDefault();
            if (!name.trim()) return setNameError('Enter your name');
            rename.mutate();
          }}
          noValidate
        >
          <Field label="Name" error={nameError} className="grow">
            {(p) => (
              <Input
                {...p}
                value={name}
                maxLength={80}
                autoComplete="name"
                onChange={(e) => setName(e.target.value)}
              />
            )}
          </Field>
          <Button
            type="submit"
            variant="primary"
            loading={rename.isPending}
            disabled={name.trim() === user.name}
          >
            Save
          </Button>
        </form>
      </section>
      <section className="glass panel" aria-labelledby="pw-h">
        <h2 id="pw-h" className="panel__title">
          Password
        </h2>
        <form
          className="grid-form"
          onSubmit={(e) => {
            e.preventDefault();
            const errs = {};
            if (!pw.currentPassword) errs.currentPassword = 'Enter your current password';
            if (pw.newPassword.length < 10 || !/[a-z]/i.test(pw.newPassword) || !/\d/.test(pw.newPassword))
              errs.newPassword = 'Use 10–128 characters with a letter and a digit';
            setPwErrors(errs);
            if (!Object.keys(errs).length) changePw.mutate();
          }}
          noValidate
        >
          {pwErrors._form && <p className="span-2 field__error">{pwErrors._form}</p>}
          <Field label="Current password" error={pwErrors.currentPassword}>
            {(p) => (
              <Input
                {...p}
                type="password"
                autoComplete="current-password"
                value={pw.currentPassword}
                onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })}
              />
            )}
          </Field>
          <Field label="New password" error={pwErrors.newPassword} help="Common passwords are rejected">
            {(p) => (
              <Input
                {...p}
                type="password"
                autoComplete="new-password"
                value={pw.newPassword}
                onChange={(e) => setPw({ ...pw, newPassword: e.target.value })}
              />
            )}
          </Field>
          <div className="span-2">
            <Button type="submit" loading={changePw.isPending}>
              Change password
            </Button>
          </div>
        </form>
      </section>
      <section className="glass panel" aria-labelledby="theme-h">
        <h2 id="theme-h" className="panel__title">
          Appearance
        </h2>
        <div className="row row--between">
          <p className="small">System follows your device. Your choice is remembered on this browser.</p>
          <ThemeToggle />
        </div>
      </section>
    </div>
  );
}
