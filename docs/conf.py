# Configuration file for the Sphinx documentation builder.
#
# This file only contains a selection of the most common options. For a full
# list see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Path setup --------------------------------------------------------------

# If extensions (or modules to document with autodoc) are in another directory,
# add these directories to sys.path here. If the directory is relative to the
# documentation root, use os.path.abspath to make it absolute, like shown here.
#
import datetime
import os
import re
import sys

# Path to python sources, for doc generation on readthedocs
source_path = os.path.abspath('..')
sys.path.insert(0, source_path)
print(f'appended {source_path}')


# -- Project information -----------------------------------------------------

# Only those 4 parameters have to be modified for each specific repository
project = 'powsybl-open-loadflow'
module_name = "powsybl-open-loadflow"
github_repository = "https://github.com/powsybl/powsybl-open-loadflow/"

# Build year for the copyright
copyright_year = f'2018-{ datetime.datetime.now().year }'

# Find the version and release information.
# We have a single source of truth for our version number: the project's pom.xml file.
# This next bit of code reads from it.
file_with_version = os.path.join(source_path, "pom.xml")
with open(file_with_version) as f:
    next_line_contains_version = False
    for line in f:
        if next_line_contains_version == False:
            m = re.match(r'^ {4}\<artifactId\>' + module_name + r'\<\/artifactId\>', line)
            if m:
                next_line_contains_version = True
        else:
            m = re.match(r'^ {4}\<version\>(.*)\<\/version\>', line)
            if m:
                __version__ = m.group(1)
                # The short X.Y version.
                version = ".".join(__version__.split(".")[:2])
                # The full version, including alpha/beta/rc tags.
                release = __version__
                break
    else:  # AKA no-break
        version = release = "dev"


# -- General configuration ---------------------------------------------------

# Add any Sphinx extension module names here, as strings. They can be
# extensions coming with Sphinx (named 'sphinx.ext.*') or your custom
# ones.
extensions = ['sphinx.ext.autodoc',
              'sphinx.ext.autosectionlabel',
              'sphinx.ext.autosummary',
              'sphinx.ext.viewcode',
              'sphinx.ext.doctest',
              'sphinx.ext.napoleon',
              'sphinx.ext.todo',
              'sphinx.ext.intersphinx',
              'sphinx_tabs.tabs',
              'myst_parser',
              # Extension used to add a "copy" button on code blocks
              'sphinx_copybutton',
              # Converts SVG figures to PDF for the LaTeX/PDF builder (pure-Python
              # CairoSVG backend, so no system binary is required on Read the Docs).
              'sphinxcontrib.cairosvgconverter']
myst_enable_extensions = [
    "amsmath",
    "colon_fence",
    "dollarmath",
    "attrs_inline"
]
myst_heading_anchors = 6
myst_dmath_double_inline = True
numfig = True

# Single source of truth for the math macros of the "Modeling and equations"
# reference. Each entry is either "name": "definition" (no arguments) or
# "name": ["definition", nargs]. This dict feeds BOTH the HTML renderer (MathJax,
# via mathjax3_config) AND the PDF renderer (the Sphinx LaTeX builder, via the
# generated latex_elements preamble below), so the two can never drift.
_MATH_MACROS = {
    "bm": [r"\boldsymbol{#1}", 1],
    "code": [r"\texttt{#1}", 1],
    # nicefrac is a LaTeX package command, not a MathJax built-in;
    # emulate its slanted small fraction a/b so converted math renders.
    "nicefrac": [r"{}^{#1}\!/\!_{#2}", 2],
    "jj": r"\mathrm{j}",
    "e": r"\mathrm{e}",
    "Re": r"\operatorname{Re}",
    "Im": r"\operatorname{Im}",
    "conj": [r"\overline{#1}", 1],
    "dd": r"\mathrm{d}",
    "pdv": [r"\frac{\partial #1}{\partial #2}", 2],
    "abs": [r"\left\lvert #1 \right\rvert", 1],
    "norm": [r"\left\lVert #1 \right\rVert", 1],
    "T": r"^{\mathsf{T}}",
    "Vc": r"\underline{V}",
    "Ic": r"\underline{I}",
    "Sc": r"\underline{S}",
    "Yc": r"\underline{Y}",
    "vv": r"\boldsymbol{v}",
    "xx": r"\boldsymbol{x}",
    "ff": r"\boldsymbol{f}",
    "gv": r"\boldsymbol{g}",
    "bv": r"\boldsymbol{b}",
    "Jmat": r"\boldsymbol{J}",
    "Ymat": r"\boldsymbol{Y}",
    "Amat": r"\boldsymbol{A}",
    "Lmat": r"\boldsymbol{L}",
    "Umat": r"\boldsymbol{U}",
    "Pmat": r"\boldsymbol{P}",
    "real": r"\mathbb{R}",
    "pu": r"\,\text{p.u.}",
    "SB": r"S_{\mathrm{B}}",
    "PTDF": r"\mathrm{PTDF}",
    "smat": r"\boldsymbol{S}",
    "rhsv": r"\boldsymbol{r}",
}

# HTML: feed the macros to MathJax. The textmacros extension makes escaped
# characters such as \_ render correctly inside \texttt{...} (e.g. ZERO_V).
mathjax3_config = {
    "loader": {"load": ["[tex]/textmacros"]},
    "tex": {
        "packages": {"[+]": ["textmacros"]},
        "macros": _MATH_MACROS,
    }
}

# PDF: the Sphinx LaTeX builder passes math to real LaTeX, so the same macros
# must be \newcommand'd in the preamble. \Re and \Im already exist in LaTeX, so
# they are \renewcommand'd. The macros are emitted from _MATH_MACROS above.
_LATEX_PREDEFINED = {"Re", "Im"}
def _latex_macro_defs(macros):
    lines = []
    for name, val in macros.items():
        body, nargs = (val[0], val[1]) if isinstance(val, list) else (val, 0)
        cmd = "renewcommand" if name in _LATEX_PREDEFINED else "newcommand"
        argspec = f"[{nargs}]" if nargs else ""
        lines.append(rf"\{cmd}{{\{name}}}{argspec}{{{body}}}")
    return "\n".join(lines)

latex_engine = "xelatex"  # native Unicode (π, thin spaces) used in the docs
# Map the top toctree level (the loadflow/security/sensitivity sections) to LaTeX
# \part, so their chapters become \chapter and the in-chapter headings \section/
# \subsection -- otherwise the deep nesting pushes content down to \paragraph and
# the PDF table of contents collapses.
latex_toplevel_sectioning = "part"
latex_elements = {
    # Show parts, chapters and sections in the PDF ToC (Sphinx otherwise emits
    # \setcounter{tocdepth}{1}); injected just before the ToC is typeset.
    "tableofcontents": r"\setcounter{tocdepth}{1}\sphinxtableofcontents",
    "preamble": r"""
\usepackage{amssymb,mathtools}
""" + _latex_macro_defs(_MATH_MACROS) + r"""
% Make the math macros degrade to plain text inside PDF bookmarks, so headings
% that contain math (e.g. "Why $\Jmat$ is factorised transposed") do not crash
% hyperref. Disabling the building blocks is enough: the named macros expand
% through them to readable text.
\pdfstringdefDisableCommands{%
  \def\boldsymbol#1{#1}\def\mathrm#1{#1}\def\mathsf#1{#1}\def\mathbb#1{#1}%
  \def\mathtt#1{#1}\def\texttt#1{#1}\def\operatorname#1{#1}\def\text#1{#1}%
  \def\underline#1{#1}\def\overline#1{#1}\def\frac#1#2{#1/#2}%
  \def\left{}\def\right{}\def\lvert{|}\def\rvert{|}\def\lVert{\|}\def\rVert{\|}%
}
""",
}

# Add any paths that contain templates here, relative to this directory.
templates_path = ['_templates']

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files.
# This pattern also affects html_static_path and html_extra_path.
exclude_patterns = ['_build', 'Thumbs.db', '.DS_Store', 'README.md', '**/README.md']

# Reference sections generation
autosectionlabel_prefix_document = True
autosectionlabel_maxdepth = 2


# -- Options for HTML output -------------------------------------------------

# The theme to use for HTML and HTML Help pages.  See the documentation for
# a list of builtin themes.
#
html_theme = "furo"

html_title = f"{project} v{release}"

html_logo = '_static/logos/logo_lfe_powsybl.svg'
html_favicon = "_static/favicon.ico"

html_context = {
    "copyright_year": copyright_year,
    "sidebar_logo_href": "https://powsybl.readthedocs.io/",
    "github_repository": github_repository
}

html_theme_options = {
    # the following 3 lines enable edit button
    "source_repository": github_repository,
    "source_branch": "main",
    "source_directory": "docs/",
}

# Add any paths that contain custom static files (such as style sheets) here,
# relative to this directory. They are copied after the builtin static files,
# so a file named "default.css" will overwrite the builtin "default.css".
html_static_path = ['_static']
html_css_files = ['styles/styles.css']

todo_include_todos = True

# Links to external documentations : python 3 and pandas
intersphinx_mapping = {
    "powsyblcore": ("https://powsybl.readthedocs.io/projects/powsybl-core/en/latest/", None)
}
intersphinx_disabled_reftypes = ["*"]

# Generate one file per method
autosummary_generate = True


# -- Dependencies versions ---------------------------------------------------
# This part will automatically look in the pom.xml to find versions corresponding to the dependencies whose
# documentation is used in the present one, except if it's a SNAPSHOT version or if a specific version has been chosen
# in intersphinx_mapping

# Get the URL without the default version
def extract_base_url(url):
    default_version = "latest"

    m = re.match(r'(^https\:\/\/.*)' + default_version + r'\/$', url)
    if m:
        return m.group(1)

# Replace the default version in the URL with the version from the pom.xml
def replace_versions(intersphinx_mapping, file):
    with open(file) as f:
        for line in f:
            m = re.match(r'^ {8}\<(.*)\.version\>(.*)\<\/(.*)\.version\>', line)
            if m and m.group(1) == m.group(3):
                dependency = m.group(1)
                version = m.group(2)
                if "SNAPSHOT" not in version and dependency in intersphinx_mapping:
                    url_start = extract_base_url(intersphinx_mapping[dependency][0])
                    if url_start:
                        intersphinx_mapping[dependency] = (url_start + version + "/", None)
            if "</properties>" in line:
                break
    return intersphinx_mapping

intersphinx_mapping = replace_versions(intersphinx_mapping, file_with_version)