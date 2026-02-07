/**
 * Core API Models for GitHub Component Testing Platform
 * Defines TypeScript interfaces for all API requests and responses
 */

// ============================================
// Repository & Component Models
// ============================================

export interface CloneResponse {
  status: string;
  localPath: string;
  repoName: string;
  message: string;
  componentsSummary?: ComponentsSummary;
  componentsByType?: ComponentsByType;
}

export interface ComponentsSummary {
  total: number;
  components: number;
  pages: number;
  elements: number;
}

export interface ComponentsByType {
  components: DiscoveredComponent[];
  pages: DiscoveredComponent[];
  elements: DiscoveredComponent[];
}

export interface DiscoveredComponent {
  name: string;
  typescriptPath: string;
  templatePath: string | null;
  stylesPath: string | null;
  type: 'COMPONENT' | 'PAGE' | 'ELEMENT';
  relativePath: string;
}

export interface ComponentInfo {
  className: string;
  selector: string;
  filePath: string;
}

export interface RepoInfo {
  repoId: string;
  localPath: string;
  repoUrl: string;
  availableComponents: string[];
  discoveredComponents?: AnalysisComponent[];
  clonedAt?: Date;
}

// ============================================
// Test Configuration Models
// ============================================

export type TestType = 'accessibility' | 'performance' | 'unit';
export type ActionMode = 'report' | 'suggest' | 'autofix';
export type ComponentStatus = 'passed' | 'failed' | 'running' | 'pending' | 'serious';

export interface AnalysisComponent {
  name: string;
  tsPath: string;
  htmlPath: string;
  stylesPath: string;
  relativePath: string;
}

export interface AnalysisConfig {
  repoPath: string;
  repoId?: string;
  componentClassName?: string;
  componentNames?: string[];
  component?: AnalysisComponent[];
  testTypes?: TestType[];
  tests: TestType[];
}

export interface SuggestFixConfig extends AnalysisConfig {
  // Extends AnalysisConfig for SSE suggest-fixes endpoint
}

export interface AutoFixConfig extends AnalysisConfig {
  maxIterations: number;
  port: number;
}

// ============================================
// SSE Event Models
// ============================================

export type SSEEventType =
  | 'started'
  | 'progress'
  | 'component_status'
  | 'fix_suggestion'
  | 'completed'
  | 'error'
  | 'summary'
  | 'component-result'
  | 'component_result'
  | 'done';

export interface SSEEvent {
  type: SSEEventType;
  data: any;
}

export interface SSEStartedEvent {
  sessionId: string;
  repoPath: string;
  timestamp: number;
}

export interface SSEProgressEvent {
  message: string;
  phase: string;
  currentStep: number;
  totalSteps: number;
  timestamp: number;
}

export interface SSEComponentStatus {
  componentName: string;
  testType: TestType;
  tool: string;
  status: 'running' | 'completed' | 'failed' | 'stopped';
  message?: string;
  metadata?: any;
  timestamp: number;
}

export interface SSECompletedEvent {
  summary: string;
  finalReport: {
    sessionId: string;
    status: string;
  };
  timestamp: number;
}

export interface SSEErrorEvent {
  message: string;
  error?: string;
  timestamp: number;
}

export interface SSESummaryEvent {
  repoId: string;
  summary: {
    totalComponents: number;
    overallStatus: string;
    overallScore: number;
    testResults: {
      [key: string]: {
        status: string;
        averageScore: number;
        passed: number;
        warned: number;
        failed: number;
      };
    };
    componentScores: {
      fullName: string;
      accessibility: {
        score: number;
        status: string;
      } | null;
      performance: any | null;
    }[];
  };
}

// New interfaces for rich details
export interface ActionableDetail {
  contrastRatio?: number;
  backgroundColor?: string;
  foregroundColor?: string;
  expectedRatio?: string;
  violationType?: string;
  cssClass?: string;
  failureMessages?: string[];
  suggestedFix?: string;
  suggestedColorOptions?: {
    status: string;
    ratio: string;
    color: string;
  }[];
  howToFix?: string;
  missingAttribute?: string;
  exampleFix?: string;
  target?: string[];
  wcagGuideline?: string;
  whyBad?: string;
}

export interface ResultIssue {
  id: string;
  severity: string;
  line: number;
  element: string;
  suggestion: string;
  actionableDetails?: ActionableDetail;
}

export interface SSEComponentResultEvent {
  result: {
    component: {
      name: string;
      relativePath: string;
      fullName: string;
      tsPath: string;
      htmlPath: string;
      stylesPath: string;
    };
    accessibility: {
      status: string;
      score: number;
      violations: {
        critical: number;
        serious: number;
        moderate: number;
        minor: number;
        total: number;
      };
      passThreshold: {
        minScore: number;
        maxCritical: number;
        maxSerious: number;
      };
      details: {
        file: string;
        issues: ResultIssue[];
      }[];
    } | null;
    performance: any | null;
  };
  timestamp: number;
}

// ============================================
// Test Results & Violations
// ============================================

export interface Violation {
  id: string;
  impact: string;
  description: string;
  help: string;
  nodes?: ViolationNode[];
}

export interface ViolationNode {
  html: string;
  target: string[];
  failureSummary?: string;
}

export interface PerformanceMetrics {
  loadTime: number;
  renderTime: number;
  tti: number; // Time to Interactive
  performanceScore: number;
  bundleSize?: number;
  memoryUsage?: number;
  fps?: number;
}

export interface FixSuggestion {
  componentName: string;
  testType: TestType;
  violations: {
    violationCount?: number;
    warningCount?: number;
    violations?: Violation[];
    metrics?: PerformanceMetrics;
  };
  suggestedFix: string;
  explanation: string;
  filePath: string;
  severity: 1 | 2 | 3; // Low | Medium | High
  timestamp: number;
}

// ============================================
// Test Results Models
// ============================================

export type TestStatus = 'passed' | 'failed' | 'running' | 'pending';

export interface TestResult {
  componentName: string;
  testType: TestType;
  status: ComponentStatus;
  iterations?: IterationDetail[];
  violations?: {
    violationCount?: number;
    warningCount?: number;
    violations?: Violation[];
  };
  metrics?: PerformanceMetrics;
  suggestedFix?: string;
  explanation?: string;
  filePath?: string;
  severity?: 1 | 2 | 3;
  errorMessage?: string;
  timestamp?: number;
  details?: {
    file: string;
    issues: ResultIssue[];
  }[];

  // Legacy fields (deprecated but kept for compatibility)
  component?: string;
  perfScore?: number;
  a11yScore?: number;
  fixes?: string[];
  iterationDetails?: IterationDetail[];
  startedAt?: Date;
  completedAt?: Date;
}

export interface IterationDetail {
  iteration: number;
  stage: string;
  performancePercent?: number;
  a11yScore?: number;
  loadTime?: number;
  renderTime?: number;
  tti?: number;
  status: 'passed' | 'failed' | 'serious';
  fixes?: FixLink[];
  timestamp: number;
  violations?: {
    violationCount?: number;
    warningCount?: number;
    violations?: Violation[];
  };
  metrics?: PerformanceMetrics;
}

export interface FixLink {
  description: string;
  filePath: string;
  lineNumber?: number;
  severity?: 'low' | 'medium' | 'high';
}

// ============================================
// API Response Models
// ============================================

export interface ReportResponse {
  sessionId: string;
  repoPath: string;
  components: TestResult[];
  results: TestResult[]; // Alias for components
  summary: {
    totalComponents: number;
    passed: number;
    failed: number;
    totalViolations: number;
  };
  timestamp: number;
}

export interface FixResult {
  success: boolean;
  componentName: string;
  iterations: number;
  finalStatus: TestStatus;
  appliedFixes: AppliedFix[];
  beforeAfter: {
    before: TestResult;
    after: TestResult;
  };
}

export interface AppliedFix {
  filePath: string;
  testType: TestType;
  description: string;
  appliedAt: Date;
}

// ============================================
// API Error Models
// ============================================

export interface ApiError {
  message: string;
  statusCode: number;
  error?: string;
  details?: any;
}

// ============================================
// Form Models
// ============================================

export interface CloneRequest {
  repoUrl: string;
}

export interface TestComponentRequest {
  repoPath: string;
  componentClassName: string;
  tests: TestType[];
  port?: number;
}

// ============================================
// Tool Status Models
// ============================================

export interface ToolStatus {
  tool: string;
  status: 'starting' | 'in-progress' | 'completed' | 'failed' | 'stopped';
  message: string;
  metadata?: any;
  timestamp: number;
  result?: TestResult;
  suggestion?: FixSuggestion;
}

export interface GroupedComponentResult {
  componentName: string;
  results: TestResult[];
  tools: ToolStatus[];
  overallStatus: ComponentStatus;
  totalIssues: number;
}

// ============================================
// UI State Models
// ============================================

export interface WorkflowState {
  repoInfo: RepoInfo | null;
  selectedComponents: string[];
  selectedTests: TestType[];
  actionMode: ActionMode;
  results: TestResult[];
  summary?: SSESummaryEvent['summary'];
  fixSuggestions: FixSuggestion[];
  isAnalyzing: boolean;
  currentPhase: string;
  error: string | null;
}

export interface SelectableComponent {
  className: string;
  selector: string;
  filePath: string;
  selected: boolean;
}
