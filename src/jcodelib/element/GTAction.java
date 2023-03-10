package jcodelib.element;

import com.github.gumtreediff.actions.model.*;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class GTAction {

	public String actionType;
	public String codeType;
	public Action action;
	public List<GTAction> children;
	public String charStr;
	private int size;
	public int startPos;
	public long runTimeOfEditScript;

	public GTAction(Action action){
		this.action = action;
		this.actionType = getActionType(action);
		this.codeType = ASTNode.nodeClassForType(action.getNode().getType()).getSimpleName();;
		this.children = new ArrayList<GTAction>();
		this.charStr = "";
		this.size = 0;
		this.startPos = 0;
		this.runTimeOfEditScript = 0;
	}

	public GTAction(Action action, CompilationUnit srcCu, CompilationUnit dstCu){
		this.action = action;
		this.actionType = getActionType(action);
		this.codeType = ASTNode.nodeClassForType(action.getNode().getType()).getSimpleName();;
		this.children = new ArrayList<GTAction>();
		setCharStr(srcCu, dstCu);
		this.size = 0;
	}

	public void setCharStr(CompilationUnit srcCu,  CompilationUnit dstCu){
		StringBuffer sb = new StringBuffer();
		sb.append(actionType);
		sb.append("\t");
		sb.append(convertNodeType(action.getNode().toShortString()));
		sb.append("[");
		if(actionType.equals("INS")){
			sb.append(dstCu.getLineNumber(action.getNode().getPos()));
			this.startPos = dstCu.getLineNumber(action.getNode().getPos());
		}else{
			sb.append(srcCu.getLineNumber(action.getNode().getPos()));
			this.startPos = srcCu.getLineNumber(action.getNode().getPos());
		}
		sb.append("]");
		if(actionType.equals("MOV")){
			Move move = (Move)action;
			sb.append("\tTO\t");
			sb.append(convertNodeType(move.getParent().toShortString()));
			sb.append("[");
			sb.append(dstCu.getLineNumber(move.getParent().getPos()));
			sb.append("]");
		}else if(actionType.equals("UPD")){
			Update update = (Update)action;
			sb.append("\tTO\t");
			sb.append(update.getValue());
		}
		this.charStr = sb.toString();
	}

	@Override
	public String toString(){
		StringBuffer sb = new StringBuffer();
		try {
			sb.append(new String(this.charStr.getBytes(), "UTF-8"));
			for(GTAction child : children){
				sb.append("\n\t");
				sb.append(child.toString());
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return sb.toString();
	}

	public int size(){
		if(size == 0){
			size = 1;
			for(GTAction action : children){
				size += action.size();
			}

		}
		return size;
	}

	public static String getActionType(Action action) {
		String actionType = "";
		if(action instanceof Insert){
			actionType = "INS";
		}else if(action instanceof Delete){
			actionType = "DEL";
		}else if(action instanceof Update){
			actionType = "UPD";
		}else if(action instanceof Move){
			actionType = "MOV";
		}
		return actionType;
	}

	public String convertNodeType(String actionString){
		Pattern nodeTypePattern = Pattern.compile("([0-9]+)(@@)");
		java.util.regex.Matcher matcher = nodeTypePattern.matcher(actionString);
		if(matcher.find()){
			int nodeType = Integer.parseInt(matcher.group(1));
			return actionString.replaceAll(nodeType+"@@", ASTNode.nodeClassForType(nodeType).getSimpleName()+"@@");
		}
		return actionString;
	}
}
