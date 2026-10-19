import ReactMarkdown from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';

const SAFE_SCHEME = /^(https?:|mailto:)/i;

/**
 * Untrusted Markdown (comments, descriptions, AI answers): no raw HTML, sanitised output, and only
 * http/https/mailto links, opened safely (§8.4 XSS).
 */
export function Markdown({ children }) {
  return (
    <div className="prose">
      <ReactMarkdown
        skipHtml
        rehypePlugins={[rehypeSanitize]}
        urlTransform={(url) => (SAFE_SCHEME.test(url) ? url : '')}
        components={{
          a: ({ href, children: c }) =>
            href ? (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {c}
              </a>
            ) : (
              <span>{c}</span>
            ),
          img: () => null,
        }}
      >
        {children || ''}
      </ReactMarkdown>
    </div>
  );
}
