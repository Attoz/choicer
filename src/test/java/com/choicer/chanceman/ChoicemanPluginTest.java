package com.choicer.chanceman;

import com.choicer.chanceman.ChoicemanPlugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ChoicemanPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ChoicemanPlugin.class);
		RuneLite.main(args);
	}
}