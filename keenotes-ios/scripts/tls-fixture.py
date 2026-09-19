#!/usr/bin/env python3
"""Local self-signed TLS server. It never logs request bodies, tokens or JWS."""
import http.server
import ssl
import sys

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.send_response(500)
        self.end_headers()
    def log_message(self, *args):
        pass

server = http.server.HTTPServer(('127.0.0.1', int(sys.argv[3])), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(sys.argv[1], sys.argv[2])
server.socket = context.wrap_socket(server.socket, server_side=True)
print('TLS fixture ready', flush=True)
server.serve_forever()
