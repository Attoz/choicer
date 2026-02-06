package com.choicer;

import com.choicer.ChoicerPlugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ChoicerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ChoicerPlugin.class);
		RuneLite.main(args);
	}
}