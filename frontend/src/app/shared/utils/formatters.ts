import { ComponentStatus } from '../../core/models/api.models';

const TOOL_NAME_MAP: Record<string, string> = {
  'dev-server': 'Dev Server',
  'metadata': 'Metadata Extraction',
  'harness': 'Test Harness',
  'deployment': 'Deployment',
  'accessibility': 'Accessibility Test',
  'accessibility-fix': 'AI Fix Generation',
  'performance': 'Performance Test',
  'ai-suggestion': 'AI Fix Suggestion',
};

export function formatToolName(tool: string): string {
  return TOOL_NAME_MAP[tool] || tool.split('-').map(word =>
    word.charAt(0).toUpperCase() + word.slice(1)
  ).join(' ');
}

export function formatTimestamp(timestamp: number): string {
  return new Date(timestamp).toLocaleString();
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

export function mapToolStatusToBadge(status: string): ComponentStatus {
  switch (status) {
    case 'completed':
    case 'stopped':
    case 'stopping':
      return 'passed';
    case 'failed':
      return 'failed';
    case 'starting':
    case 'running':
    case 'in-progress':
      return 'running';
    default:
      return 'pending';
  }
}

export function mapSummaryStatus(status: string | undefined): ComponentStatus {
  if (!status) return 'pending';
  if (status === 'warning') return 'serious';
  const validStatuses: ComponentStatus[] = ['passed', 'failed', 'running', 'pending', 'serious'];
  return validStatuses.includes(status as ComponentStatus)
    ? (status as ComponentStatus)
    : 'pending';
}

export function mapSeverityToStatus(severity: number): ComponentStatus {
  if (severity === 1) return 'serious';
  if (severity === 2) return 'failed';
  return 'pending';
}
