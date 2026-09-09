import socket
import threading
import sys

def forward(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try: src.close()
        except: pass
        try: dst.close()
        except: pass

def handle_client(client_socket):
    try:
        remote = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        remote.connect(('127.0.0.1', 19000))
    except Exception as e:
        client_socket.close()
        return

    t1 = threading.Thread(target=forward, args=(client_socket, remote), daemon=True)
    t2 = threading.Thread(target=forward, args=(remote, client_socket), daemon=True)
    t1.start()
    t2.start()

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', 19001))
    server.listen(128)
    print("S3 LAN TCP Proxy running: 0.0.0.0:19001 -> 127.0.0.1:19000", flush=True)
    while True:
        client, addr = server.accept()
        handle_client(client)

if __name__ == '__main__':
    main()
