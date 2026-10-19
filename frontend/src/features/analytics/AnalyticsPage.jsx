import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import * as Tabs from '@radix-ui/react-tabs';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { problem } from '../../api/client.js';
import { analytics, tasks } from '../../api/endpoints.js';
import { Button } from '../../components/ui/Button.jsx';
import { FilterChip } from '../../components/ui/Chips.jsx';
import { EmptyState, ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Select } from '../../components/ui/Field.jsx';
import { Segmented } from '../../components/ui/Segmented.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { addDaysIso, formatDate, todayIso } from '../../utils/format.js';
import { useProject } from '../projects/ProjectLayout.jsx';

// Recessive axes/grid and flat marks (§4.6.7). One y-scale per chart: tasks and hours are toggled,
// never plotted on two axes.
const AXIS = {
  stroke: 'var(--glass-edge)',
  tick: { fill: 'var(--text-muted)', fontSize: 12 },
  tickLine: false,
};

export default function AnalyticsPage() {
  const project = useProject();
  useDocumentTitle('Analytics', project.key);
  const [tab, setTab] = useState('burndown');
  const done = useQuery({
    queryKey: ['done-count', project.key],
    queryFn: () => tasks.list(project.key, { status: 'DONE', size: 1 }),
  });

  if (done.isSuccess && done.data.totalItems < 3) {
    return (
      <section className="glass panel">
        <EmptyState title="Not enough history yet">Charts appear after a few tasks are completed.</EmptyState>
      </section>
    );
  }
  return (
    <Tabs.Root value={tab} onValueChange={setTab} className="page">
      <Tabs.List className="tabs__list clay-inset" aria-label="Charts">
        <Tabs.Trigger value="burndown" className="tabs__trigger">
          Burndown
        </Tabs.Trigger>
        <Tabs.Trigger value="velocity" className="tabs__trigger">
          Velocity
        </Tabs.Trigger>
      </Tabs.List>
      <Tabs.Content value="burndown">
        <Burndown projectKey={project.key} />
      </Tabs.Content>
      <Tabs.Content value="velocity">
        <Velocity projectKey={project.key} />
      </Tabs.Content>
    </Tabs.Root>
  );
}

function ChartTooltip({ active, payload, label, format }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="tooltip glass glass--raised chart-tip">
      <strong>{formatDate(label)}</strong>
      {payload.map((p) => (
        <span key={p.dataKey} className="row">
          <span className="legend-swatch" style={{ background: p.color }} aria-hidden="true" />
          {p.name}: {format(p.value)}
        </span>
      ))}
    </div>
  );
}

function Burndown({ projectKey }) {
  const [range, setRange] = useState('30');
  const [custom, setCustom] = useState({ from: addDaysIso(todayIso(), -60), to: todayIso() });
  const [table, setTable] = useState(false);
  const params =
    range === 'custom' ? custom : { from: addDaysIso(todayIso(), -Number(range) + 1), to: todayIso() };
  const invalid = range === 'custom' && (!custom.from || !custom.to || custom.to < custom.from);
  const q = useQuery({
    queryKey: ['burndown', projectKey, params],
    queryFn: () => analytics.burndown(projectKey, params),
    enabled: !invalid,
  });
  const points = q.data?.points || [];
  const first = points[0];
  const last = points[points.length - 1];
  const gap = last ? Math.round((last.remainingHours - last.idealHours) * 10) / 10 : 0;
  const summary = first
    ? `Remaining work went from ${first.remainingHours} h to ${last.remainingHours} h; ${Math.abs(gap)} h ${gap >= 0 ? 'above' : 'below'} the ideal line.`
    : '';
  const p = q.error && problem(q.error);

  return (
    <section className="glass panel stack" aria-labelledby="bd-h">
      <div className="row row--between">
        <h2 id="bd-h">Burndown</h2>
        <div className="row" role="group" aria-label="Date range">
          {[
            ['14', '14 days'],
            ['30', '30 days'],
            ['90', '90 days'],
            ['custom', 'Custom'],
          ].map(([v, l]) => (
            <FilterChip key={v} pressed={range === v} onToggle={() => setRange(v)}>
              {l}
            </FilterChip>
          ))}
        </div>
      </div>
      {range === 'custom' && (
        <div className="row">
          <Field label="From">
            {(f) => (
              <Input
                {...f}
                type="date"
                value={custom.from}
                onChange={(e) => setCustom({ ...custom, from: e.target.value })}
              />
            )}
          </Field>
          <Field label="To" help="Up to 180 days" error={invalid ? 'End must be on or after start' : null}>
            {(f) => (
              <Input
                {...f}
                type="date"
                value={custom.to}
                onChange={(e) => setCustom({ ...custom, to: e.target.value })}
              />
            )}
          </Field>
        </div>
      )}
      {q.isPending && !invalid && <Skeleton height={300} />}
      {q.isError &&
        (p.code === 'RANGE_TOO_LARGE' ? (
          <Notice kind="warning">Choose a range of 180 days or less.</Notice>
        ) : (
          <ErrorNotice error={q.error} onRetry={q.refetch} />
        ))}
      {q.isSuccess && (
        <figure className="chart">
          <figcaption>{summary}</figcaption>
          {table ? (
            <DataTable
              caption="Burndown by day"
              columns={['Date', 'Remaining (h)', 'Ideal (h)']}
              rows={points.map((pt) => [formatDate(pt.date), pt.remainingHours, pt.idealHours])}
            />
          ) : (
            <div className="chart__plot" aria-hidden="true">
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="var(--glass-edge)" vertical={false} />
                  <XAxis dataKey="date" tickFormatter={formatDate} minTickGap={24} {...AXIS} />
                  <YAxis width={44} {...AXIS} axisLine={false} unit=" h" />
                  <Tooltip
                    content={<ChartTooltip format={(v) => `${v} h`} />}
                    cursor={{ stroke: 'var(--text-muted)', strokeWidth: 1 }}
                  />
                  <Legend wrapperStyle={{ fontSize: 13, color: 'var(--text-2)' }} />
                  <Line
                    name="Remaining"
                    type="linear"
                    dataKey="remainingHours"
                    stroke="var(--primary)"
                    strokeWidth={2}
                    dot={false}
                    activeDot={{ r: 4 }}
                    isAnimationActive={false}
                  />
                  <Line
                    name="Ideal"
                    type="linear"
                    dataKey="idealHours"
                    stroke="var(--text-muted)"
                    strokeWidth={2}
                    strokeDasharray="4 4"
                    dot={false}
                    isAnimationActive={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
          <Button size="sm" variant="ghost" offlineOk aria-pressed={table} onClick={() => setTable(!table)}>
            {table ? 'View as chart' : 'View as table'}
          </Button>
        </figure>
      )}
    </section>
  );
}

function Velocity({ projectKey }) {
  const [weeks, setWeeks] = useState(8);
  const [measure, setMeasure] = useState('tasksCompleted');
  const [table, setTable] = useState(false);
  const q = useQuery({
    queryKey: ['velocity', projectKey, weeks],
    queryFn: () => analytics.velocity(projectKey, weeks),
  });
  const series = q.data?.series || [];
  const total = series.reduce((s, w) => s + w[measure], 0);
  const label = measure === 'tasksCompleted' ? 'tasks' : 'h';
  const avg = series.length ? Math.round((total / series.length) * 10) / 10 : 0;

  return (
    <section className="glass panel stack" aria-labelledby="vel-h">
      <div className="row row--between">
        <h2 id="vel-h">Velocity</h2>
        <div className="row">
          <Segmented
            label="Measure"
            value={measure}
            onChange={setMeasure}
            options={[
              { value: 'tasksCompleted', label: 'Tasks' },
              { value: 'hoursCompleted', label: 'Hours' },
            ]}
          />
          <label className="visually-hidden" htmlFor="vel-weeks">
            Weeks
          </label>
          <Select id="vel-weeks" value={weeks} onChange={(e) => setWeeks(Number(e.target.value))}>
            {[4, 8, 12, 26].map((w) => (
              <option key={w} value={w}>
                Last {w} weeks
              </option>
            ))}
          </Select>
        </div>
      </div>
      {q.isPending && <Skeleton height={300} />}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
      {q.isSuccess && (
        <figure className="chart">
          <figcaption>
            {weeks} weeks: {Math.round(total * 10) / 10} {label} completed, about {avg} {label} per week.
          </figcaption>
          {table ? (
            <DataTable
              caption="Completed per week"
              columns={['Week starting', 'Tasks', 'Hours']}
              rows={series.map((w) => [formatDate(w.weekStart), w.tasksCompleted, w.hoursCompleted])}
            />
          ) : (
            <div className="chart__plot" aria-hidden="true">
              <ResponsiveContainer width="100%" height={300}>
                <BarChart
                  data={series}
                  margin={{ top: 8, right: 16, bottom: 0, left: 0 }}
                  barCategoryGap="30%"
                >
                  <CartesianGrid stroke="var(--glass-edge)" vertical={false} />
                  <XAxis dataKey="weekStart" tickFormatter={formatDate} {...AXIS} />
                  <YAxis width={44} allowDecimals={measure !== 'tasksCompleted'} {...AXIS} axisLine={false} />
                  <Tooltip
                    content={<ChartTooltip format={(v) => `${v} ${label}`} />}
                    cursor={{ fill: 'var(--hover-tint)' }}
                  />
                  <Bar
                    name={measure === 'tasksCompleted' ? 'Tasks completed' : 'Hours completed'}
                    dataKey={measure}
                    fill="var(--primary)"
                    radius={[4, 4, 0, 0]}
                    isAnimationActive={false}
                  />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          <Button size="sm" variant="ghost" offlineOk aria-pressed={table} onClick={() => setTable(!table)}>
            {table ? 'View as chart' : 'View as table'}
          </Button>
        </figure>
      )}
    </section>
  );
}

function DataTable({ caption, columns, rows }) {
  return (
    <div className="table-scroll">
      <table className="table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {r.map((cell, j) =>
                j === 0 ? (
                  <th key={j} scope="row">
                    {cell}
                  </th>
                ) : (
                  <td key={j}>{cell}</td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
