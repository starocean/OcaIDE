package ocaml.editors;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ocaml.OcamlPlugin;
import ocaml.debugging.DebugVisuals;
import ocaml.editor.completion.CompletionJob;
import ocaml.editor.syntaxcoloring.OcamlEditorColors;
import ocaml.editors.util.OcamlCharacterPairMatcher;
import ocaml.natures.OcamlNatureMakefile;
import ocaml.parser.Def;
import ocaml.popup.actions.CompileProjectPopupAction;
import ocaml.preferences.PreferenceConstants;
import ocaml.views.outline.OcamlOutlineControl;
import ocaml.views.outline.OutlineJob;
import ocaml.views.outline.SynchronizeOutlineJob;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.PaintManager;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.MatchingCharacterPainter;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

/**
 * Configures the OCaml editor, and manages the events raised by this editor,
 * by overloading methods of the TextEditor class.
 */
public class OcamlEditor extends TextEditor {

	/**
	 * used to notify that the outline job is finished (notified by OutlineJob
	 * to OcamlHyperlinkDetector)
	 */
	public Object outlineSignal = new Object();

	private OcamlOutlineControl outline;

	protected PaintManager paintManager;

	protected MatchingCharacterPainter matchingCharacterPainter;

	private OutlineJob outlineJob = null;

	private SynchronizeOutlineJob synchronizeOutlineJob = null;

	public interface ICursorPositionListener {
		void cursorPositionChanged(ITextEditor textEditor, Point selectedRange);
	}

	private List<ICursorPositionListener> cursorPositionListeners = new ArrayList<ICursorPositionListener>();

	public static final String ML_EDITOR_ID = "ocaml.editors.mlEditor";
	public static final String MLI_EDITOR_ID = "ocaml.editors.mliEditor";

	public OcamlEditor() {
		this.setSourceViewerConfiguration(new OcamlSourceViewerConfig(this));
		// this.setRangeIndicator(new DefaultRangeIndicator());
	}

	/** The debug cursor (as a red I-beam) */
	private DebugVisuals caret;

	public ITextViewer getTextViewer() {
		return this.getSourceViewer();
	}

	@Override
	protected void createActions() {
		super.createActions();

		final ISourceViewer sourceViewer = this.getSourceViewer();
		final StyledText textWidget = sourceViewer.getTextWidget();
		final OcamlSourceViewerConfig sourceViewerConfig =
				(OcamlSourceViewerConfig) this.getSourceViewerConfiguration();


		try {
			paintManager = new PaintManager(getSourceViewer());
			matchingCharacterPainter = new MatchingCharacterPainter(getSourceViewer(),
					new OcamlCharacterPairMatcher());
			matchingCharacterPainter.setColor(new Color(Display.getCurrent(),
					new RGB(160, 160, 160)));
			paintManager.addPainter(matchingCharacterPainter);

			/*
			 * AnnotationPainter annotationPainter = new
			 * AnnotationPainter(getSourceViewer(), null);
			 * annotationPainter.addAnnotationType
			 * ("Ocaml.ocamlSyntaxErrorMarker");
			 *
			 * paintManager.addPainter(annotationPainter);
			 */

		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		try {
			caret = new DebugVisuals(textWidget);
			textWidget.addPaintListener(caret);

			IPath file = getPathOfFileBeingEdited();
			if (file != null)
				caret.setFilename(file.lastSegment());
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		// parse the OCaml libraries in a background thread
		try {
			CompletionJob job = new CompletionJob("Parsing ocaml library mli files", null);
			job.setPriority(CompletionJob.INTERACTIVE); 	// Trung changes priority
			job.schedule();
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		try {
			OcamlPlugin.getInstance().checkPaths();
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		// synchronize with outline when double-click mouse
		textWidget.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
			}

			@Override
			public void mouseDown(MouseEvent e) {
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				if (sourceViewerConfig.isContentAssistantActive() || e == null)
					return;

				synchronizeOutline();
			}
		});


		// Trung: don't rebuild outline when text is changed because it
		// slow down the system
		/*
		final ISourceViewer viewer = this.getSourceViewer();
		final OcamlSourceViewerConfig viewerConfig = (OcamlSourceViewerConfig) this.getSourceViewerConfiguration();
		viewer.addTextListener(new ITextListener() {

			public void textChanged(TextEvent event) {
				// Trung: rebuild only when content assistant is inactive
				if (viewerConfig.isContentAssistantActive() || event == null)
					return;

				DocumentEvent docEvent = event.getDocumentEvent();
				if (docEvent == null)
					return;

				String text = docEvent.getText().trim();
				if (!text.isEmpty())
					rebuildOutline(50, false); // don't sync outline with editor
			}
		});
		*/

	}

	@Override
	/** Initializes the context for the OCaml editor shortcuts */
	protected void initializeKeyBindingScopes() {
		setKeyBindingScopes(new String[] { "Ocaml.editor.context" });
	}

	@Override
	public void doSetInput(IEditorInput input) throws CoreException {

		super.doSetInput(input);

		if (this.outline != null) {
			rebuildOutline(50, false);
		}

		IProject project = this.getProject();
		if (project == null)
			return;

		// parse the project interfaces in a background thread
		CompletionJob job = new CompletionJob("Parsing ocaml project mli files", project);
		job.setPriority(CompletionJob.INTERACTIVE);	// Trung changes priority
		job.schedule();

		if (input instanceof IFileEditorInput) {
			IFileEditorInput fileEditorInput = (IFileEditorInput) input;

			if (caret != null)
				caret.setFilename(fileEditorInput.getFile().getName());
		}

		/*
		 * if (this.fOutlinePage != null) this.fOutlinePage.setInput(input);
		 */
	}



	/**
	 * We give the outline to Eclipse when it asks for an adapter with the
	 * outline class.
	 */
	@Override
	public Object getAdapter(@SuppressWarnings("unchecked") Class required) {
		IPath file = this.getPathOfFileBeingEdited();

		if (file != null && IContentOutlinePage.class.equals(required)) {
			if ("mlp".equals(file.getFileExtension()) || "ml4".equals(file.getFileExtension()))
				return null;

			if (this.outline == null)
				this.outline = new OcamlOutlineControl(this);
			rebuildOutline(50, false);
			return this.outline;
		}
		return super.getAdapter(required);
	}

	/*
	 * public Hashtable<Integer,String> getHashTypes() { return
	 * this.hashtableTypes; }
	 */

	@Override
	public void createPartControl(Composite parent) {
		super.createPartControl(parent);
		StyledText styledText = this.getSourceViewer().getTextWidget();
		styledText.setTabs(getTabSize());
	}



	public static int getTabSize() {
		return OcamlPlugin.getInstance().getPreferenceStore().getInt(
				PreferenceConstants.P_EDITOR_TABS);
	}

	public static boolean spacesForTabs() {
		return OcamlPlugin.getInstance().getPreferenceStore().getBoolean(
				PreferenceConstants.P_EDITOR_SPACES_FOR_TABS);
	}

	/**
	 * Return a string representing a tabulation as defined by user preferences.
	 * Can be a single tab character or multiple spaces.
	 */
	public static String getTab() {
		if (!spacesForTabs())
			return "\t";
		else {
			int nSpaces = getTabSize();
			StringBuilder builder = new StringBuilder(nSpaces);
			for (int i = 0; i < nSpaces; i++)
				builder.append(" ");
			return builder.toString();
		}
	}

	public void redraw() {
		getSourceViewer().getTextWidget().redraw();
	}

	/** Return the caret position in the editor */
	public int getCaretOffset() {
		ISelection sel = this.getSelectionProvider().getSelection();
		if (sel instanceof TextSelection) {
			TextSelection selection = (TextSelection) sel;
			return selection.getOffset();
		}

		OcamlPlugin.logError("selection is not instanceof TextSelection");
		return -1;
	}

	public static class LineColumn {
		private final int line;
		private final int column;

		public LineColumn(int line, int column) {
			this.line = line;
			this.column = column;
		}

		public int getLine() {
			return line;
		}

		public int getColumn() {
			return column;
		}
	}

	/** @return the current selection offset in the editor, as a (line,column) position. */
	public LineColumn getSelectionLineColumn() {
		ISelection sel = getSelectionProvider().getSelection();
		if (sel instanceof TextSelection) {
			TextSelection textSelection = (TextSelection) sel;
			IDocument document = getDocumentProvider().getDocument(getEditorInput());
			int offset = textSelection.getOffset();
			try {
				int lineNumber = document.getLineOfOffset(offset);
				int column = offset - document.getLineOffset(lineNumber);
				return new LineColumn(lineNumber, column);
			} catch (BadLocationException e) {
				OcamlPlugin.logError(e);
				return null;
			}

		}
		OcamlPlugin.logError("selection is not instanceof TextSelection");
		return null;
	}

	/** Synchronize the outline with the cursor line in the editor */
	public void synchronizeOutline() {

		if (this.outline == null)
			return;

		if (synchronizeOutlineJob == null)
			synchronizeOutlineJob = new SynchronizeOutlineJob("Synchronizing outline with editor");
		else if (synchronizeOutlineJob.getState() == SynchronizeOutlineJob.RUNNING)
			return;
		// only one job at a time
		else
			synchronizeOutlineJob.cancel();

		synchronizeOutlineJob.setPriority(CompletionJob.DECORATE);
		synchronizeOutlineJob.setEditor(this);
		synchronizeOutlineJob.setOutlineJob(outlineJob);
		synchronizeOutlineJob.schedule(50);
	}

	public IContainer getWorkingLocation() {
		FileEditorInput editorInput = (FileEditorInput) this.getEditorInput();
		if (editorInput == null || editorInput.getFile() == null) {
			return null;
		}
		return editorInput.getFile().getParent();
	}

	public IProject getProject() {
		IEditorInput editorInput = this.getEditorInput();

		if (editorInput instanceof FileEditorInput) {
			FileEditorInput fileEditorInput = (FileEditorInput) editorInput;
			IFile file = fileEditorInput.getFile();
			if (file != null)
				return file.getProject();
		}

		return null;
	}

	public IFile getFile(String filename) {
		IContainer container = this.getWorkingLocation();
		Path path = new Path(filename);
		IFile file = container.getFile(path);
		return file;
	}

	public IFile getFileBeingEdited() {
		IEditorInput editorInput = this.getEditorInput();

		if (editorInput instanceof FileEditorInput) {
			FileEditorInput fileEditorInput = (FileEditorInput) editorInput;
			return fileEditorInput.getFile();
		}

		return null;
	}

	public IPath getPathOfFileBeingEdited() {
		IEditorInput editorInput = this.getEditorInput();

		if (editorInput instanceof FileEditorInput) {
			FileEditorInput fileEditorInput = (FileEditorInput) editorInput;
			return fileEditorInput.getFile().getLocation();
		}

		// file external to the workspace
		if (editorInput instanceof FileStoreEditorInput) {
			FileStoreEditorInput fileStoreEditorInput = (FileStoreEditorInput) editorInput;

			return new Path(new File(fileStoreEditorInput.getURI()).getPath());
		}

		return null;
	}

	/*
	 * public String getAnnotFileName(String filename) { return
	 * filename.substring(0, filename.lastIndexOf(".")) + ".annot"; }
	 */

	public String getFullPathFileName(String filename) {
		// String workspaceDir = "";
		// IContainer container = getWorkingLocation();
		return this.getFile(filename).getLocation().toString();
	}

	public String getFullPath(String filename) {
		String workspaceDir = "";
		IContainer container = this.getWorkingLocation();
		workspaceDir = container.getLocation().toString();
		return workspaceDir;
	}

	public IProgressMonitor getMonitor() {
		return this.getProgressMonitor();
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		super.doSave(monitor);

		// rebuild Outline when file is saved
		rebuildOutline(50, false);

		boolean bMakefileNature = false;
		try {
			IProject project = this.getProject();
			if (project != null)
				bMakefileNature = project.getNature(OcamlNatureMakefile.ID) != null;
		} catch (CoreException e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		/*
		 * If this project is a makefile project, then we compile manually each
		 * time the user saves (because the automatic compiling provided by
		 * Eclipse is disabled on makefile projects)
		 */
		if (bMakefileNature) {
			IWorkspace ws = ResourcesPlugin.getWorkspace();
			IWorkspaceDescription desc = ws.getDescription();
			if (desc.isAutoBuilding())
				CompileProjectPopupAction.compileProject(this.getProject());
		}
	}

	/*
	 * @Override protected void editorContextMenuAboutToShow(IMenuManager menu)
	 * { IFile file = this.getFileBeingEdited();
	 * super.editorContextMenuAboutToShow(menu);
	 *
	 * MenuManager ocamlGroup = new MenuManager("OCaml"); menu.add(new
	 * Separator()); menu.add(ocamlGroup); ocamlGroup.add(new
	 * GenDocAction("GenDoc", file)); }
	 */

	/*
	 * public OcamlOutlineControl getOutlinePage() { if (this.fOutlinePage ==
	 * null) this.fOutlinePage = new
	 * OcamlOutlineControl(this.getDocumentProvider(), this); return
	 * this.fOutlinePage; }
	 */

	public void rebuildOutline(int delay, boolean syncWithEditor) {

		// invalidate previous definitions
		this.codeDefinitionsTree = null;
		this.codeOutlineDefinitionsTree = null;

		IEditorInput input = this.getEditorInput();
		IDocument document = this.getDocumentProvider().getDocument(input);
		// String doc = document.get();

		if (outlineJob == null)
			outlineJob = new OutlineJob("Rebuilding outline", syncWithEditor);
		// else if (outlineJob.getState() == OutlineJob.RUNNING)
		// return;
		// only one Job at a time
		else
			outlineJob.cancel();

		outlineJob.setPriority(CompletionJob.SHORT);
		outlineJob.setOutline(this.outline);
		outlineJob.setDoc(document);
		outlineJob.setEditor(this);

		outlineJob.schedule(delay);
	}

	/**
	 * when the caret position changes, we synchronize the outline with the
	 * editor
	 */
	@Override
	public void handleCursorPositionChanged() {
		super.handleCursorPositionChanged();

		fireCursorPositionChanged(getTextViewer().getSelectedRange());

		final OcamlEditor editor = this;
		OcamlTextHover hover = new OcamlTextHover(editor);
		ITextViewer viewer = this.getTextViewer();
		int offset = this.getCaretOffset();
		IRegion region = hover.getHoverRegion(viewer, offset);

		String message = "";

		if (OcamlPlugin.getInstance().getPreferenceStore().getBoolean(
				PreferenceConstants.P_SHOW_MARKERS_IN_STATUS_BAR)) {
			message = hover.getMarkerInfoOneLine(viewer, region);
		}

		// only display type info when there isn't any markers
		if (message.isEmpty()
				&& OcamlPlugin.getInstance().getPreferenceStore().getBoolean(
						PreferenceConstants.P_SHOW_TYPES_IN_STATUS_BAR)) {
			message = hover.getTypeInfoOneLine(viewer, region);
			if (message.contains("more lines..."))
				message = "";
		}

		final String statusMessage = message;
		Display.getCurrent().asyncExec(new Runnable() {
			public void run() {
				editor.setStatusLineMessage(statusMessage);
			}
		});
	}

	@Override
	public void setStatusLineMessage(String message) {
		super.setStatusLineMessage(message);
	}

	private Def codeDefinitionsTree = null;
	private Def codeOutlineDefinitionsTree = null;

	/**
	 * Allows the code parsing Job to send back the definitions tree to the
	 * editor
	 */
	public synchronized void setDefinitionsTree(Def def) {
		this.codeDefinitionsTree = def;
	}

	public void setOutlineDefinitionsTree(Def outlineDefinitions) {
		this.codeOutlineDefinitionsTree = outlineDefinitions;

	}

	/** Return the last definitions tree computed, or null if none */
	public synchronized Def getDefinitionsTree() {
		return this.codeDefinitionsTree;
	}

	/** Return the last outline definitions tree computed, or null if none */
	public synchronized Def getOutlineDefinitionsTree() {
		return this.codeOutlineDefinitionsTree;
	}

	public void highlight(int offset) {
		IDocument document = this.getDocumentProvider().getDocument(this.getEditorInput());

		IRegion region = null;
		try {
			region = document.getLineInformationOfOffset(offset);
			if (region != null)
				this.selectAndReveal(region.getOffset(), 0);
		} catch (BadLocationException e) {
			OcamlPlugin.logError("ocaml plugin error (bad location)", e);
			return;
		}
	}

	public void highlight(int line, int column1, int column2) {
		IDocument document = this.getDocumentProvider().getDocument(this.getEditorInput());

		try {
			int offset = document.getLineOffset(line - 1) + column1 - 1;
			int length = column2 - column1;
			this.selectAndReveal(offset, length);
		} catch (BadLocationException e) {
			OcamlPlugin.logError("ocaml plugin error (bad location)", e);
		}
	}

	public void highlight(int line, int column) {
		IDocument document = this.getDocumentProvider().getDocument(this.getEditorInput());

		try {
			int offset = document.getLineOffset(line - 1) + column - 1;
			int length = 0;
			this.selectAndReveal(offset, length);
		} catch (BadLocationException e) {
			OcamlPlugin.logError("ocaml plugin error (bad location)", e);
		}
	}

	public OcamlOutlineControl getOutline() {
		return outline;
	}

	public void addCursorPositionListener(ICursorPositionListener cursorPositionListener) {
		if (!this.cursorPositionListeners.contains(cursorPositionListener)
				&& cursorPositionListener != null)
			this.cursorPositionListeners.add(cursorPositionListener);
	}

	public void removeCursorPositionListener(ICursorPositionListener cursorPositionListener) {
		this.cursorPositionListeners.remove(cursorPositionListener);

	}

	public void fireCursorPositionChanged(Point selectedRange) {
		for (ICursorPositionListener cursorPositionListener : this.cursorPositionListeners) {
			cursorPositionListener.cursorPositionChanged(this, selectedRange);
		}
	}

}
