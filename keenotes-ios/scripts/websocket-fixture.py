#!/usr/bin/env python3
"""Isolated protocol fixture for connection-generation tests; logs no payloads."""
import base64, hashlib, http.server, json, socket, struct, threading
clients = {}
encrypted_content = None
lock = threading.Lock()

def frame(payload):
    data = json.dumps(payload).encode()
    if len(data) <= 125:
        return bytes([0x81, len(data)]) + data
    return bytes([0x81, 126]) + struct.pack('!H', len(data)) + data

def batch(space, identifier):
    return {'type': 'sync_batch', 'notes': [{'id': identifier, 'content': 'fixture account ' + space, 'channel': 'test', 'created_at': '2026-01-01 00:00:00'}]}

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_POST(self):
        global encrypted_content
        encrypted_content = json.loads(self.rfile.read(int(self.headers['Content-Length'])))['content']
        self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()

    def do_GET(self):
        if self.path == '/release-a':
            with lock:
                current = clients.get('a')
            if current:
                current.sendall(frame(batch('late-a', 99)))
            self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers(); return
        space = self.path.split('/')[1]
        key = self.headers.get('Sec-WebSocket-Key')
        if not key:
            self.send_error(400); return
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101); self.send_header('Upgrade', 'websocket'); self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept); self.end_headers()
        sock = self.connection
        with lock: clients[space] = sock
        try:
            sock.recv(8192)  # Client handshake, no payload logging.
            if space == 'encrypted':
                payload = batch(space, 1)
                payload['notes'] = [dict(payload['notes'][0], id=i, content=encrypted_content) for i in range(1, 13)]
                sock.sendall(frame(payload))
                sock.sendall(frame({'type': 'sync_complete', 'total_synced': 12, 'last_sync_id': 12}))
            else:
                sock.sendall(frame(batch(space, 1 if space == 'a' else 2)))
                sock.sendall(frame({'type': 'sync_complete', 'total_synced': 1, 'last_sync_id': 1 if space == 'a' else 2}))
            while sock.recv(8192): pass
        except (OSError, socket.error): pass
        finally:
            with lock:
                if clients.get(space) is sock: clients.pop(space, None)
        self.close_connection = True
    def log_message(self, *args): pass
print('WebSocket fixture ready', flush=True)
http.server.ThreadingHTTPServer(('127.0.0.1', 18444), Handler).serve_forever()
