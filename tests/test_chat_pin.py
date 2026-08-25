"""Pin-to-bottom rules used by the chat list (mirrors ChatScreen / app.js)."""

from __future__ import annotations

import unittest


def list_near_bottom(
    *,
    total: int,
    last_visible_index: int | None,
    last_offset: int,
    last_size: int,
    viewport_end: int,
    threshold: int = 120,
) -> bool:
    if total == 0:
        return True
    if last_visible_index is None:
        return True
    if last_visible_index < total - 1:
        return False
    bottom = last_offset + last_size
    return bottom <= viewport_end + threshold


def chat_near_bottom(scroll_height: int, scroll_top: int, client_height: int, threshold: int = 120) -> bool:
    return scroll_height - scroll_top - client_height <= threshold


class ChatPinTests(unittest.TestCase):
    def test_empty_list_is_near_bottom(self) -> None:
        self.assertTrue(
            list_near_bottom(
                total=0,
                last_visible_index=None,
                last_offset=0,
                last_size=0,
                viewport_end=800,
            )
        )

    def test_last_item_fully_in_view(self) -> None:
        self.assertTrue(
            list_near_bottom(
                total=5,
                last_visible_index=4,
                last_offset=500,
                last_size=200,
                viewport_end=700,
            )
        )

    def test_scrolled_up_from_tall_thought_is_not_near_bottom(self) -> None:
        # Last thought is visible but its bottom is well below the viewport —
        # user is reading the top of the bubble.
        self.assertFalse(
            list_near_bottom(
                total=3,
                last_visible_index=2,
                last_offset=0,
                last_size=4000,
                viewport_end=800,
            )
        )

    def test_earlier_item_visible_is_not_near_bottom(self) -> None:
        self.assertFalse(
            list_near_bottom(
                total=10,
                last_visible_index=7,
                last_offset=100,
                last_size=200,
                viewport_end=800,
            )
        )

    def test_web_chat_near_bottom_threshold(self) -> None:
        self.assertTrue(chat_near_bottom(2000, 1200, 700, 120))
        self.assertFalse(chat_near_bottom(2000, 200, 700, 120))


if __name__ == "__main__":
    unittest.main()
