"""Notification channels — abstraction + polymorphism.

Notifier (ABC) defines the interface; GmailNotifier sends real email,
NullNotifier is the offline stand-in. The rest of the app never cares
which one it holds.
"""
from __future__ import annotations

import smtplib
import ssl
from abc import ABC, abstractmethod
from email.message import EmailMessage

from .models import Student


class Notifier(ABC):
    """Abstract notification channel."""

    @abstractmethod
    def send(self, student: Student, subject: str, body: str) -> bool:
        """Return True only if the message was actually sent."""


class NullNotifier(Notifier):
    """Offline mode — deliberately does nothing."""

    def send(self, student: Student, subject: str, body: str) -> bool:
        return False


class GmailNotifier(Notifier):
    """Sends real email via Gmail SMTP using an App Password."""

    HOST = "smtp.gmail.com"
    PORT = 465

    def __init__(self, address: str, app_password: str, timeout: int = 10):
        if not address or not app_password:
            raise ValueError("Gmail address and app password are required.")
        self.address = address
        self._app_password = app_password  # encapsulated — never exposed
        self.timeout = timeout

    def send(self, student: Student, subject: str, body: str) -> bool:
        if not student.can_receive_email:
            return False
        msg = EmailMessage()
        msg["From"] = self.address
        msg["To"] = student.gmail
        msg["Subject"] = subject
        msg.set_content(body)
        with smtplib.SMTP_SSL(self.HOST, self.PORT,
                              context=ssl.create_default_context(),
                              timeout=self.timeout) as smtp:
            smtp.login(self.address, self._app_password)
            smtp.send_message(msg)
        return True
