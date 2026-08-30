package org.ultramine.gradle.task;

import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.ultramine.gradle.internal.UMFileUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class SideSplitTask extends DefaultTask
{
	private static final String SIDEONLY_DESK = "Lcpw/mods/fml/relauncher/SideOnly;";
	private File inputDir;
	private boolean outputServerSide = true;
	private boolean outputClientSide = true;
	/*
	 * The same directory as inputDir, declared again as a file collection: that
	 * is what InputChanges reports per-file changes against.
	 */
	private final ConfigurableFileCollection incrementalInput = getProject().getObjects().fileCollection();
	private File taskDir = new File(getProject().getLayout().getBuildDirectory().get().getAsFile(), getName());
	private File classesServer = new File(taskDir, "classes_server");
	private File classesClient = new File(taskDir, "classes_client");

	public SideSplitTask() throws IOException
	{
		FileUtils.forceMkdir(classesServer);
		FileUtils.forceMkdir(classesClient);
	}

	@Internal
	public File getInputDir()
	{
		return inputDir;
	}

	public void setInputDir(File inputDir)
	{
		this.inputDir = inputDir;
		this.incrementalInput.setFrom(inputDir);
	}

	@Incremental
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public FileCollection getIncrementalInput()
	{
		return incrementalInput;
	}

	@Input
	public boolean isOutputServerSide()
	{
		return outputServerSide;
	}

	public void setOutputServerSide(boolean outputServerSide)
	{
		this.outputServerSide = outputServerSide;
	}

	@Input
	public boolean isOutputClientSide()
	{
		return outputClientSide;
	}

	public void setOutputClientSide(boolean outputClientSide)
	{
		this.outputClientSide = outputClientSide;
	}

	@OutputDirectory
	public File getServerClasses()
	{
		return classesServer;
	}

	@OutputDirectory
	public File getClientClasses()
	{
		return classesClient;
	}

	@TaskAction
	void doAction(InputChanges inputs) throws IOException
	{
		if(!inputs.isIncremental())
		{
			FileUtils.cleanDirectory(classesServer);
			FileUtils.cleanDirectory(classesClient);
			getProject().fileTree(inputDir).visit(new FileVisitor(){
				@Override
				public void visitDir(FileVisitDetails dirDetails)
				{

				}

				@Override
				public void visitFile(FileVisitDetails fileDetails)
				{
					processClass(fileDetails.getPath());
				}
			});
		}
		else
		{
			Set<File> dirsToCheck = new HashSet<File>();
			for(FileChange change : inputs.getFileChanges(incrementalInput))
			{
				if(change.getChangeType() != ChangeType.REMOVED)
				{
					processClass(change.getFile());
					continue;
				}

				File file = new File(classesServer, getRelPath(change.getFile()));
				file.delete();
				dirsToCheck.add(file.getParentFile());
				file = new File(classesClient, getRelPath(change.getFile()));
				file.delete();
				dirsToCheck.add(file.getParentFile());
			}

			for(File file : dirsToCheck)
				if(file.exists() && UMFileUtils.isDirEmptyRecursive(file.toPath()))
				{
					FileUtils.deleteDirectory(file);
					File parent = file.getParentFile();
					if(UMFileUtils.isDirEmpty(parent.toPath()))
						FileUtils.deleteDirectory(parent);
				}
		}
	}

	private String getRelPath(File file)
	{
		return UMFileUtils.getRelativePath(inputDir, file);
	}

	private void processClass(File file)
	{
		if(file.isDirectory())
			return;
		processClass(getRelPath(file));
	}

	private void processClass(String path)
	{
		try
		{
			byte[] cls = FileUtils.readFileToByteArray(new File(inputDir, path));
			if(outputServerSide)
			{
				byte[] serverCls = processClass(cls, "SERVER");
				if(serverCls != null)
					writeClass(classesServer, path, serverCls);
			}
			if(outputClientSide)
			{
				byte[] clientCls = processClass(cls, "CLIENT");
				if(clientCls != null)
					writeClass(classesClient, path, clientCls);
			}
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private void writeClass(File dir, String path, byte[] cls) throws IOException
	{
		FileUtils.writeByteArrayToFile(new File(dir, path), cls);
	}

	private byte[] processClass(byte[] input, String side)
	{
		ClassNode classNode = new ClassNode();
		ClassReader classReader = new ClassReader(input);
		classReader.accept(classNode, 0);

		if(remove(classNode.visibleAnnotations, side))
			return null;

		Iterator<FieldNode> fields = classNode.fields.iterator();
		while(fields.hasNext())
		{
			FieldNode field = fields.next();
			if(remove(field.visibleAnnotations, side))
				fields.remove();
		}
		Iterator<MethodNode> methods = classNode.methods.iterator();
		while(methods.hasNext())
		{
			MethodNode method = methods.next();
			if(remove(method.visibleAnnotations, side))
				methods.remove();
		}

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		classNode.accept(writer);
		return writer.toByteArray();
	}

	private boolean remove(List<AnnotationNode> anns, String side)
	{
		if(anns == null)
			return false;
		for(AnnotationNode ann : anns)
		{
			if(ann.desc.equals(SIDEONLY_DESK) && ann.values != null)
			{
				for(int x = 0; x < ann.values.size() - 1; x += 2)
				{
					Object key = ann.values.get(x);
					Object value = ann.values.get(x+1);
					if(key.equals("value") && value instanceof String[] && !((String[])value)[1].equals(side))
						return true;
				}
			}
		}
		return false;
	}
}
