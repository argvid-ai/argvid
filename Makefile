.PHONY: doctor test context-check project-check

doctor:
	@./tools/doctor

context-check:
	@./tools/context-check

project-check:
	@./tools/project-check

test: context-check project-check
	@python3 -m unittest discover -s tests -p 'test_*.py'
	@python3 -c 'import sys, unittest; suite = unittest.defaultTestLoader.discover("conformance/tests", pattern="test_*.py"); sys.exit(not unittest.TextTestRunner().run(suite).wasSuccessful()) if suite.countTestCases() else print("conformance: pending (no executable tests yet)")'
