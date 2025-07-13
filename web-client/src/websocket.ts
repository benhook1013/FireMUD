export type MessageHandler = (data: unknown) => void;

class WebSocketClient {
  private socket: WebSocket | null = null;
  private handlers = new Set<MessageHandler>();

  connect(path = '/ws'): void {
    if (this.socket) {
      return;
    }
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${protocol}://${window.location.host}${path}`;
    this.socket = new WebSocket(url);

    this.socket.onmessage = (event) => {
      let payload: unknown = event.data;
      try {
        payload = JSON.parse(event.data);
      } catch {
        // non-JSON payloads are allowed
      }
      this.handlers.forEach((h) => h(payload));
    };

    this.socket.onclose = () => {
      this.socket = null;
    };
  }

  send(data: unknown): void {
    if (!this.socket) return;
    const payload = typeof data === 'string' ? data : JSON.stringify(data);
    this.socket.send(payload);
  }

  addMessageHandler(handler: MessageHandler): void {
    this.handlers.add(handler);
  }

  removeMessageHandler(handler: MessageHandler): void {
    this.handlers.delete(handler);
  }

  disconnect(): void {
    this.socket?.close();
    this.socket = null;
  }
}

export const websocketClient = new WebSocketClient();
