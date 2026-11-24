"""Bounded JUnit reader for this standalone, offline compatibility sample."""
import os
import stat
from pyexpat import ExpatError, ParserCreate


MAX_BYTES = 2 * 1024 * 1024
MAX_ELEMENTS = 16_384
MAX_DEPTH = 64


class ParseError(ValueError):
    pass


class JUnitElement:
    def __init__(self, tag, attributes):
        self.tag = tag
        self.attrib = attributes
        self.children = []
        self._text = []

    @property
    def text(self):
        return "".join(self._text) if self._text else None

    def iter(self, tag):
        if self.tag == tag:
            yield self
        for child in self.children:
            yield from child.iter(tag)

    def findall(self, tag):
        return [child for child in self.children if child.tag == tag]

    def find(self, tag):
        return next((child for child in self.children if child.tag == tag), None)


def open_report_descriptor(path):
    if os.name != "nt":
        return os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)

    import _winapi
    import msvcrt

    # Open the reparse point itself. Transfer ownership of this exact handle
    # to the CRT so type checks and the bounded read refer to the same object.
    file_share_read = 0x00000001
    file_flag_open_reparse_point = 0x00200000
    handle = _winapi.CreateFile(os.fspath(path), _winapi.GENERIC_READ, file_share_read,
                                0, _winapi.OPEN_EXISTING, file_flag_open_reparse_point, 0)
    try:
        if _winapi.GetFileType(handle) != _winapi.FILE_TYPE_DISK:
            raise ParseError("JUnit report must be a regular disk file")
        return msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY | os.O_NOINHERIT)
    except BaseException:
        _winapi.CloseHandle(handle)
        raise


def read_report(path):
    descriptor = open_report_descriptor(path)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if os.name == "nt" and info.st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise OSError("JUnit report must not be a reparse point")
        if not stat.S_ISREG(info.st_mode):
            raise ParseError("JUnit report must be a regular file")
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ParseError("JUnit report exceeds byte limit")
    return data


def parse_report(data):
    if len(data) > MAX_BYTES:
        raise ParseError("JUnit report exceeds byte limit")
    parser = ParserCreate(namespace_separator="}")
    parser.buffer_text = True
    stack = []
    root = None
    count = 0

    def start(tag, attributes):
        nonlocal root, count
        count += 1
        if count > MAX_ELEMENTS or len(stack) >= MAX_DEPTH:
            raise ParseError("JUnit report exceeds element or nesting limit")
        node = JUnitElement(tag, attributes)
        if stack:
            stack[-1].children.append(node)
        else:
            root = node
        stack.append(node)

    def characters(value):
        if stack and not stack[-1].children:
            stack[-1]._text.append(value)

    def reject_declaration(*_args):
        raise ParseError("JUnit DTDs and entity declarations are forbidden")

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda _tag: stack.pop()
    parser.CharacterDataHandler = characters
    parser.StartDoctypeDeclHandler = reject_declaration
    parser.EntityDeclHandler = reject_declaration
    parser.ExternalEntityRefHandler = reject_declaration
    try:
        parser.Parse(data, True)
    except ExpatError as exc:
        raise ParseError(str(exc)) from exc
    return root
