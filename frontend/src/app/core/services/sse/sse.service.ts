import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  SSEEvent,
  SSEEventType,
  SuggestFixConfig,
} from '../../models/api.models';
import { API_BASE_URL } from '../../tokens/api.tokens';

/**
 * Server-Sent Events (SSE) service
 * Handles real-time streaming connections with automatic reconnection
 */
@Injectable({
  providedIn: 'root',
})
export class SseService {
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly reconnectDelay = 2000; // 2 seconds
  private readonly maxReconnectAttempts = 3;

  private activeConnections = new Map<string, AbortController>();

  /**
   * Connect to suggest-fixes SSE endpoint
   * POST /api/workflow/suggest-fixes
   * @param config Suggest fix configuration
   * @returns Observable stream of SSE events
   */
  connectToSuggestFixes(config: SuggestFixConfig): Observable<SSEEvent> {
    const payload = {
      repoPath: config.repoPath,
      repoId: config.repoId,
      component: config.component,
      tests: config.tests,
    };

    const url = `${this.apiBaseUrl}/workflow/suggest-fixes`;

    return this.createPostSSEObservable(url, payload);
  }

  /**
   * Create an SSE Observable using POST and fetch
   * @param url SSE endpoint URL
   * @param payload Request body
   * @returns Observable of SSE events
   */
  private createPostSSEObservable(url: string, payload: any): Observable<SSEEvent> {
    return new Observable<SSEEvent>((observer) => {
      let reconnectAttempts = 0;
      let isClosed = false;

      const connect = async () => {
        if (isClosed) return;

        const abortController = new AbortController();
        this.activeConnections.set(url, abortController);

        try {
          const response = await fetch(url, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Accept': 'text/event-stream',
            },
            body: JSON.stringify(payload),
            signal: abortController.signal,
          });

          if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
          }

          if (!response.body) {
            throw new Error('Response body is null');
          }

          reconnectAttempts = 0;

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const blocks = buffer.split('\n\n');
            buffer = blocks.pop() || '';

            for (const block of blocks) {
              if (!block.trim()) continue;

              const event = this.parseSSEBlock(block);
              if (event) {
                observer.next(event);

                if (event.type === 'completed' || event.type === 'error') {
                  // Let the loop finish typically, or explicitly close if needed by protocol
                  // For 'completed' we often want to stop listening.
                  if (event.type === 'completed') {
                    this.closeConnection(url);
                    observer.complete();
                    return;
                  }
                }
              }
            }
          }

          if (!isClosed) {
            this.closeConnection(url);
            observer.complete();
          }

        } catch (error: any) {
          if (error.name === 'AbortError') return;

          console.error('[SSE] Connection error:', error);
          this.activeConnections.delete(url);

          if (reconnectAttempts < this.maxReconnectAttempts && !isClosed) {
            reconnectAttempts++;
            setTimeout(() => connect(), this.reconnectDelay);
          } else {
            observer.error({
              message: 'Connection lost. Maximum reconnection attempts reached.',
              error,
            });
          }
        }
      };

      connect();

      return () => {
        isClosed = true;
        this.closeConnection(url);
      };
    });
  }

  private parseSSEBlock(block: string): SSEEvent | null {
    const lines = block.split('\n');
    let eventType: SSEEventType | null = null;
    let data = '';

    for (const line of lines) {
      const colonIndex = line.indexOf(':');
      if (colonIndex === -1) continue;

      const field = line.substring(0, colonIndex).trim();
      // Use substring to get everything after the first colon, then trim whitespace
      const value = line.substring(colonIndex + 1).trim();

      if (field === 'event') {
        eventType = value as SSEEventType;
      } else if (field === 'data') {
        data = value;
      }
    }

    if (eventType && data) {
      try {
        return {
          type: eventType,
          data: JSON.parse(data)
        };
      } catch (e) {
        console.warn('[SSE] Failed to parse JSON data', data);
        return {
          type: eventType,
          data: { message: data }
        };
      }
    }
    return null;
  }

  /**
   * Close SSE connection
   * @param url Connection URL
   */
  private closeConnection(url: string): void {
    const controller = this.activeConnections.get(url);
    if (controller) {
      controller.abort();
      this.activeConnections.delete(url);
    }
  }

  /**
   * Close all active SSE connections
   */
  closeAllConnections(): void {
    this.activeConnections.forEach((_, url) => {
      this.closeConnection(url);
    });
  }

  /**
   * Get active connection count
   */
  getActiveConnectionCount(): number {
    return this.activeConnections.size;
  }
}
