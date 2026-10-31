#!/usr/bin/env python3
"""A small FTP server, enough for curl: USER/PASS, TYPE, PWD, CWD, MKD, PASV, STOR, SIZE, QUIT.

    fake_ftp.py <port> <root> <user> <password> [--read-only]

It exists so the atom's smoke can speak the real protocol instead of stubbing curl.
"""
import os
import socket
import sys
import threading

port = int(sys.argv[1])
root = os.path.abspath(sys.argv[2])
user = sys.argv[3]
password = sys.argv[4]
read_only = "--read-only" in sys.argv[5:]

os.makedirs(root, exist_ok=True)


def handle(conn: socket.socket):
    conn.sendall(b"220 fake-ftp ready\r\n")
    file_in = conn.makefile("rb")
    state = {"user": None, "cwd": "/", "pasv": None, "rest": 0}

    def reply(text):
        conn.sendall(text.encode() + b"\r\n")

    def resolve(path):
        # FTP paths are absolute on the wire; keep them inside root.
        # os.path.join(root, "/abs") would throw root away, so the leading slash is dropped first.
        if path.startswith("/"):
            relative = path.lstrip("/")
        else:
            relative = os.path.join(state["cwd"].lstrip("/"), path)
        full = os.path.normpath(os.path.join(root, relative))
        if full != root and not full.startswith(root + os.sep):
            raise PermissionError(path)
        return full

    try:
        while True:
            line = file_in.readline()
            if not line:
                break
            command, _, argument = line.decode(errors="replace").strip().partition(" ")
            upper = command.upper()

            if upper == "USER":
                state["user"] = argument
                reply("331 password please")
            elif upper == "PASS":
                if state["user"] == user and argument == password:
                    reply("230 logged in")
                else:
                    reply("530 Login denied")
            elif upper == "SYST":
                reply("215 UNIX Type: L8")
            elif upper == "FEAT":
                reply("211-Features:\r\n SIZE\r\n211 End")
            elif upper == "PWD":
                reply('257 "%s"' % state["cwd"])
            elif upper == "TYPE":
                reply("200 type set")
            elif upper == "OPTS":
                reply("200 ok")
            elif upper == "CWD":
                try:
                    target = resolve(argument)
                    if os.path.isdir(target):
                        relative = os.path.relpath(target, root).replace(os.sep, "/")
                        state["cwd"] = "/" if relative == "." else "/" + relative
                        reply("250 cwd ok")
                    else:
                        reply("550 no such directory")
                except PermissionError:
                    reply("550 nope")
            elif upper == "MKD":
                try:
                    os.makedirs(resolve(argument), exist_ok=True)
                    reply("257 created")
                except (PermissionError, OSError):
                    reply("550 cannot create")
            elif upper == "PASV":
                listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                state["pasv"] = listener
                data_port = listener.getsockname()[1]
                reply("227 Entering Passive Mode (127,0,0,1,%d,%d)" % (data_port >> 8, data_port & 0xFF))
            elif upper == "STOR":
                if read_only:
                    reply("550 read only")
                    continue
                try:
                    target = resolve(argument)
                except PermissionError:
                    reply("550 nope")
                    continue
                os.makedirs(os.path.dirname(target), exist_ok=True)
                conn.sendall(b"150 opening data connection\r\n")
                listener = state["pasv"]
                if listener is None:
                    conn.sendall(b"425 no data connection\r\n")
                    continue
                data, _ = listener.accept()
                listener.close()
                state["pasv"] = None
                with open(target, "wb") as out:
                    while True:
                        chunk = data.recv(65536)
                        if not chunk:
                            break
                        out.write(chunk)
                data.close()
                reply("226 transfer complete")
            elif upper == "SIZE":
                try:
                    reply("213 %d" % os.path.getsize(resolve(argument)))
                except (PermissionError, OSError):
                    reply("550 no such file")
            elif upper == "QUIT":
                reply("221 bye")
                break
            else:
                reply("502 not implemented")
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(16)
    print("fake-ftp listening on %d, root=%s" % (port, root), flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
