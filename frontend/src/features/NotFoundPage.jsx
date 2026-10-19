import { Link } from 'react-router';
import { Compass } from 'lucide-react';
import { EmptyState } from '../components/ui/Feedback.jsx';
import { useDocumentTitle } from '../hooks/misc.js';

/** P-14. Also used for inaccessible projects, which the API reports as 404 (no existence leak). */
export function NotFoundPage({ what = 'page', standalone }) {
  useDocumentTitle('Not found');
  const body = (
    <div className="glass panel">
      <EmptyState
        icon={<Compass aria-hidden="true" />}
        title={`We couldn't find that ${what}`}
        action={
          <Link className="btn btn--primary" to="/projects">
            Go to projects
          </Link>
        }
      >
        It may have been removed, or you may not have access to it.
      </EmptyState>
    </div>
  );
  return standalone ? <main className="auth">{body}</main> : body;
}
