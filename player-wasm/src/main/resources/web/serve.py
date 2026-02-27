"""
Simple HTTP server with COOP/COEP headers for SharedArrayBuffer support.
Run from the WASM build output directory:
    python serve.py [port]
"""
import http.server
import socketserver
import sys

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.wfile.write(b"Cross-Origin-Opener-Policy: same-origin\r\n")
        self.wfile.write(b"Cross-Origin-Embedder-Policy: require-corp\r\n")
        super().end_headers()

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
with socketserver.TCPServer(('', PORT), Handler) as httpd:
    print(f'Serving on http://localhost:{PORT}')
    httpd.serve_forever()
