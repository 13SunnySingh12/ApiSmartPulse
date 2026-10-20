-- Neon Auth (managed Better Auth) identities. In AUTH_PROVIDER=neon mode the identity provider owns
-- credentials; app users link to it by the provider's user id (JWT "sub", neon_auth.user.id) and
-- never get a local password hash. No FK into neon_auth: Neon owns that schema.
ALTER TABLE users ADD COLUMN auth_user_id UUID;
CREATE UNIQUE INDEX uq_users_auth_user_id ON users (auth_user_id);
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_credential
    CHECK (password_hash IS NOT NULL OR auth_user_id IS NOT NULL);
