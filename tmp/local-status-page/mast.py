"""One header structure shared by both status-page renderers."""

from html import escape


def render_mast(title: str, middle_html: str, middle_class: str,
                links: tuple[tuple[str, str, str], ...]) -> str:
    if middle_class not in {"refresh-space", "mast-meta"}:
        raise ValueError("unknown mast middle class")
    navigation = "".join(
        f'<a href="{escape(url, quote=True)}"><span class="nav-full">{escape(full)}</span>'
        f'<span class="nav-short">{escape(short)}</span></a>'
        for url, full, short in links
    )
    return (
        '<header class="mast"><div class="mast-inner">'
        '<div class="mast-content"><img class="mast-icon" src="/flame-ember.svg" alt="">'
        f'<h1 class="brand">{escape(title)}</h1></div>'
        f'<div class="mast-middle {middle_class}">{middle_html}</div>'
        f'<nav class="repo-links">{navigation}</nav></div></header>'
    )
