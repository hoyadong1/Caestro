# Studio platform (Supabase)

The web studio (`docs/studio`) lets people sign in, upload the scores and instruments they make, browse and like
other people's work and arrange someone else's score as their own version. Uploads wait for review; an admin
approves them, and only approved works reach the car, most liked first.

```
studio (GitHub Pages) ──sign in, upload──▶ Supabase: works · likes · profiles, bucket "packs"
                                                │ admin approves
car app ◀──car_catalog() + bucket files, parked─┘
```

## Set up

1. Create a free project at supabase.com (any region; Seoul is closest).
2. SQL editor → paste `schema.sql` → Run. It creates the tables, the row-level rules, the `car_catalog()`
   function and the public bucket `packs`.
3. Authentication → URL configuration: Site URL `https://hoyadong1.github.io/Caestro/studio/`, and the same under
   Redirect URLs. E-mail sign-in (magic link) is on by default.
4. Project settings → API: copy the Project URL and the `anon` public key into
   - `docs/studio/config.js` (`SUPABASE_URL`, `SUPABASE_KEY`) for the studio,
   - `gradle.properties` (`caestro.supabaseUrl`, `caestro.supabaseKey`) for the app, then build the APK.
   Both are public by design; the service-role key is never put anywhere in this repository.
5. Sign in to the studio once, then make that account the admin in the SQL editor:
   `update public.profiles set is_admin = true where id = (select id from auth.users where email = '…');`
6. In the studio's 🛡 review tab, "예시 곡·악기 올리기" puts the example cloud (Largo, trombone, handpan) up as
   approved works, so the car's list is not empty.

## How it fits together

- `works` holds one row per score or instrument: the card fields the car shows, the manifest, the file paths
  `<owner>/<id>/<id>.json` (and `.pcm`) in the bucket, `forked_from`, `status`, `likes`.
- Owners change their own works; any change sends a work back to review. Status, likes and review notes are
  set only by an admin or by the like counter (trigger `works_guard`).
- The car calls `rest/v1/rpc/car_catalog` with the public key and downloads files from
  `storage/v1/object/public/packs/` (`CloudLibrary.Source.platform`). It still checks every pack before
  installing it, so a broken upload can never replace a working one.
- Without a configured project the studio works offline (make, preview, zip for `docs/cloud`) and the app
  uses the example cloud on GitHub Pages.

Free projects pause after a week without use; open the dashboard to wake it before a demo.
